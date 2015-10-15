package com.hanhuy.android.irc

import java.lang.Thread.UncaughtExceptionHandler
import java.nio.charset.Charset
import java.util.Date
import javax.net.ssl.SSLContext

import android.graphics.Typeface
import android.text.{Layout, StaticLayout, TextPaint, TextUtils}
import android.text.style.TextAppearanceSpan
import android.util.{TypedValue, DisplayMetrics}
import android.view.WindowManager
import com.hanhuy.android.irc.model._

import android.app.NotificationManager
import android.content.{IntentFilter, Context, BroadcastReceiver, Intent}
import android.os.{Build, Handler, HandlerThread}
import android.app.Notification
import android.app.PendingIntent
import android.widget.{Toast, RemoteViews}
import android.support.v4.app.NotificationCompat

import com.sorcix.sirc.{Channel => SircChannel, _}
import com.sorcix.sirc.cap.{CapNegotiator, CompoundNegotiator, ServerTimeNegotiator}

import com.hanhuy.android.common._
import Futures._
import IrcManager._
import android.net.{Uri, ConnectivityManager}
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.BusEvent.{IrcManagerStop, IrcManagerStart, ChannelStatusChanged}
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Notice
import com.hanhuy.android.irc.model.BusEvent
import org.acra.ACRA

import scala.concurrent.Future
import scala.util.Try

object IrcManager {
  val log = Logcat("IrcManager")

  // notification IDs
  val RUNNING_ID = 1
  val DISCON_ID  = 2
  val PRIVMSG_ID = 3
  val MENTION_ID = 4

  val EXTRA_PAGE     = "com.hanhuy.android.irc.extra.page"
  val EXTRA_SUBJECT  = "com.hanhuy.android.irc.extra.subject"
  val EXTRA_SPLITTER = "::qicr-splitter-boundary::"

  val ACTION_NEXT_CHANNEL = "com.hanhuy.android.irc.action.NOTIF_NEXT"
  val ACTION_PREV_CHANNEL = "com.hanhuy.android.irc.action.NOTIF_PREV"
  val ACTION_CANCEL_MENTION = "com.hanhuy.android.irc.action.CANCEL_MENTION"
  val ACTION_QUICK_CHAT = "com.hanhuy.android.irc.action.QUICK_CHAT"

  var instance: Option[IrcManager] = None

  def start() = {
    instance getOrElse {
      val m = new IrcManager()
      m.start()
      m
    }
  }

  def stop[A](message: Option[String] = None, cb: Option[() => A] = None) {
    instance foreach { _.quit(message, cb) }
  }

  def running = instance exists (_.running)
}
class IrcManager extends EventBus.RefOwner {
  Application.context.systemService[NotificationManager].cancelAll()
  val version =
    Application.context.getPackageManager.getPackageInfo(
      Application.context.getPackageName, 0).versionName
  IrcConnection.ABOUT = getString(R.string.version, version)
  log.v("Creating service")
  Widgets(Application.context) // load widgets listeners
  val ircdebug = Settings.get(Settings.IRC_DEBUG)
  if (ircdebug)
    IrcDebug.setLogStream(PrintStream)
  IrcDebug.setEnabled(ircdebug)
  val filter = new IntentFilter

  filter.addAction(ACTION_NEXT_CHANNEL)
  filter.addAction(ACTION_PREV_CHANNEL)
  filter.addAction(ACTION_CANCEL_MENTION)

  private var channelHolder = Map.empty[String,MessageAppender]
  def getChannel[A](id: String): Option[A] = {
    val c = channelHolder.get(id)
    if (c.isDefined) {
      channelHolder -= id
      c.asInstanceOf[Option[A]]
    } else None
  }

  def saveChannel(id: String, c: MessageAppender): Unit = {
    channelHolder += id -> c
  }

  def getString(s: Int, args: Any*) = Application.context.getString(s,
    args map { _.asInstanceOf[Object] }: _*)

  private var lastRunning = 0l
  private var _running = false
  private var _showing = false
  def showing = _showing
  val nm = Application.context.systemService[NotificationManager]

  ServiceBus += {
    case BusEvent.MainActivityStart => _showing = true
    case BusEvent.MainActivityStop  => _showing = false
    case BusEvent.MainActivityDestroy =>
      recreateActivity foreach { page =>
        recreateActivity = None
        val intent = new Intent(Application.context, classOf[MainActivity])
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_PAGE, page)
        UiBus.post { Application.context.startActivity(intent) }
      }
    case BusEvent.PreferenceChanged(_, k) =>
      if (k == Settings.IRC_DEBUG) {
        val debug = Settings.get(Settings.IRC_DEBUG)
        if (debug)
          IrcDebug.setLogStream(PrintStream)
        IrcDebug.setEnabled(debug)
      }
    case BusEvent.ChannelMessage(ch, msg) =>
      val first = channels.keySet.toSeq sortWith { (a,b) =>
        ChannelLikeComparator.compare(a,b) < 0} headOption

      lastChannel orElse first foreach { c =>
      if (!showing && c == ch) {
        val text = getString(R.string.notif_connected_servers, connections.size)

        val n = runningNotification(text)
        nm.notify(RUNNING_ID, n)
      }
    }
  }

  instance = Some(this)
  var recreateActivity: Option[Int] = None // int = page to flip to
  var messagesId = 0
  def connected = connections.size > 0

  lazy val handlerThread = {
    val t = new HandlerThread("IrcManagerHandler")
    t.start()
    t
  }
  // used to schedule an irc ping every 30 seconds
  lazy val handler = new Handler(handlerThread.getLooper)

  def connections  = mconnections
  def _connections = m_connections
  private var mconnections   = Map.empty[Server,IrcConnection]
  private var m_connections  = Map.empty[IrcConnection,Server]

  var lastChannel: Option[ChannelLike] = None

  def channels = mchannels
  def _channels = m_channels
  private var mchannels  = Map.empty[ChannelLike,SircChannel]
  private var m_channels = Map.empty[SircChannel,ChannelLike]
  private var queries    = Map.empty[(Server,String),Query]

  def remove(id: Int) {
    _messages -= id
    _servs -= id
  }

  def remove(c: ChannelLike) {
    c match {
      case ch: Channel =>
        mchannels -= ch
      case qu: Query =>
        mchannels -= qu
        queries -= ((qu.server, qu.name.toLowerCase))
    }
  }

  // TODO find a way to automatically(?) purge the adapters
  // worst-case: leak memory on the int, but not the adapter
  def messages = _messages
  def servs    = _servs
  private var _messages = Map.empty[Int,MessageAdapter]
  private var _servs    = Map.empty[Int,Server]

  def add(idx: Int, adapter: MessageAdapter) {
    _messages += ((idx, adapter))
  }

  def newMessagesId(): Int = {
    messagesId += 1
    messagesId
  }

  def removeConnection(server: Server) {
    //Log.d(TAG, "Unregistering connection: " + server, new StackTrace)
    connections.get(server).foreach(c => {
      mconnections -= server
      m_connections -= c
    })
  }

  def addConnection(server: Server, connection: IrcConnection) {
    log.i("Registering connection: " + server + " => " + connection)
    mconnections += ((server, connection))
    m_connections += ((connection, server))

    if (!showing && _running) {
      nm.notify(RUNNING_ID, runningNotification(runningString))
    }
  }

  private def start() {
    if (!running) {
      Application.context.registerReceiver(receiver, filter)

      val connFilter = new IntentFilter
      connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
      Application.context.registerReceiver(connReceiver, connFilter)

      Application.context.startService(
        new Intent(Application.context, classOf[LifecycleService]))

      log.v("Launching autoconnect servers")
      Config.servers.foreach { s =>
        if (s.autoconnect) connect(s)
        s.messages.maximumSize = Try(
          Settings.get(Settings.MESSAGE_LINES).toInt).toOption getOrElse
          MessageAdapter.DEFAULT_MAXIMUM_SIZE
      }
      ServiceBus.send(IrcManagerStart)
    }
  }


  def queueCreateActivity(page: Int) = recreateActivity = Some(page)

  def running = _running

  var disconnectCount = 0
  def quit[A](message: Option[String] = None, cb: Option[() => A] = None) {
    instance = None
    Application.context.unregisterReceiver(receiver)
    Application.context.unregisterReceiver(connReceiver)
    nm.cancelAll()
    ServiceBus.send(BusEvent.ExitApplication)

    val count = connections.keys.size
    _running = false
    disconnectCount = 0
    Future {
      synchronized {
        // TODO wait for quit to actually complete?
        while (disconnectCount < count) {
          log.d("Waiting for disconnect: %d/%d" format (
            disconnectCount, count))
          wait()
        }
      }
      log.d("All disconnects completed, running callback: " + cb)
      cb.foreach { callback => UiBus.run { callback() } }
      nm.cancelAll()
    }
    connections.keys.foreach(disconnect(_, message, false, true))
    handlerThread.quit()
    ServiceBus.send(IrcManagerStop)
  }

  def disconnect(server: Server, message: Option[String] = None,
                 disconnected: Boolean = false, quitting: Boolean = false) {
    connections.get(server).foreach { c =>
      Future {
        try {
          val m = message getOrElse {
            Settings.get(Settings.QUIT_MESSAGE)
          }
          c.disconnect(m)
        } catch {
          case e: Exception =>
            log.e("Disconnect failed", e)
            c.setConnected(false)
            c.disconnect()
        }
        synchronized {
          disconnectCount += 1
          notify()
        }
      }
    }
    removeConnection(server) // gotta go after the foreach above
    server.state = Server.State.DISCONNECTED
    // handled by onDisconnect
    server.add(ServerInfo(getString(R.string.server_disconnected)))

    //if (disconnected && server.autoconnect) // not requested?  auto-connect
    //    connect(server)

    if (connections.size == 0) {
      // do not stop context if onDisconnect unless showing
      // do not stop context if quitting, quit() will do it
      if ((!disconnected || showing) && !quitting) {
        log.i("Stopping context because all connections closed")
        _running = false
        lastRunning = System.currentTimeMillis
      }
    }
  }

  def connect(server: Server) {
    log.v("Connecting server: %s", server)
    if (server.state == Server.State.CONNECTING ||
      server.state == Server.State.CONNECTED) {
      return
    }

    server.state = Server.State.CONNECTING
    Future(connectServerTask(server))
    _running = true
  }

  def getServers = Config.servers

  def addServer(server: Server) {
    Config.addServer(server)
    UiBus.send(BusEvent.ServerAdded(server))
  }

  def startQuery(server: Server, nick: String) {
    val query = queries.getOrElse((server, nick.toLowerCase), {
      val q = Query(server, nick)
      q add MessageLike.Query()
      queries += (((server, nick.toLowerCase),q))
      q
    })
    UiBus.send(BusEvent.StartQuery(query))
  }

  def addQuery(q: Query): Unit = {
    queries += (((q.server, q.name), q))
    mchannels += ((q,null))
  }
  def addQuery(c: IrcConnection, _nick: String, msg: String,
               sending: Boolean = false, action: Boolean = false,
               notice: Boolean = false, ts: Date = new Date) {
    val server = _connections.getOrElse(c, { return })
    val nick = if (sending) server.currentNick else _nick

    if (!Config.Ignores(nick)) {
      val query = queries.getOrElse((server, _nick.toLowerCase), {
        val q = Query(server, _nick)
        queries += (((server, _nick.toLowerCase), q))
        q
      })
      mchannels += ((query, null))


      UiBus.run {
        val m = if (notice) Notice(nick, msg, ts)
        else if (action) CtcpAction(nick, msg, ts)
        else Privmsg(nick, msg, ts = ts)
        UiBus.send(BusEvent.PrivateMessage(query, m))
        ServiceBus.send(BusEvent.PrivateMessage(query, m))

        query.add(m)
        if (!showing)
          showNotification(PRIVMSG_ID, R.drawable.ic_notify_mono_star,
            m.toString, Some(query))
      }
    }
  }

  def addChannel(c: IrcConnection, ch: SircChannel) {
    val server = _connections(c)
    var channel: ChannelLike = Channel(server, ch.getName)
    channels.keys.find(_ == channel) foreach { _c =>
      channel    = _c
      val _ch    = channels(channel)
      mchannels  -= channel
      m_channels -= _ch
    }
    mchannels  += ((channel,ch))
    m_channels += ((ch,channel))

    UiBus.run {
      val chan = channel.asInstanceOf[Channel]
      UiBus.send(BusEvent.ChannelAdded(chan))
      ServiceBus.send(BusEvent.ChannelAdded(chan))
      chan.state = Channel.State.JOINED
    }
  }

  def removeChannel(ch: Channel) {
    val sircchannel = channels(ch)
    mchannels  -= ch
    m_channels -= sircchannel
  }

  def updateServer(server: Server) = {
    Config.updateServer(server)
    UiBus.send(BusEvent.ServerChanged(server))
  }

  def deleteServer(server: Server) {
    Config.deleteServer(server)
    UiBus.send(BusEvent.ServerRemoved(server))
  }


  // TODO decouple
  def serverDisconnected(server: Server) {
    UiBus.run {
      disconnect(server, disconnected = true)
      if (_running) {
        if (connections.isEmpty) {
          lastChannel = None
          nm.notify(RUNNING_ID, runningNotification(
            getString(R.string.server_disconnected)))
        } else {
          nm.notify(RUNNING_ID, runningNotification(runningString))
        }
      }
    }
    if (!showing && _running)
      showNotification(DISCON_ID, R.drawable.ic_notify_mono_bang,
        getString(R.string.notif_server_disconnected, server.name))
  }

  // TODO decouple
  def addChannelMention(c: ChannelLike, m: MessageLike) {
    if (!showing && c.isNew(m))
      showNotification(MENTION_ID, R.drawable.ic_notify_mono_star,
        getString(R.string.notif_mention_template, c.name, m.toString), Some(c))
  }

  private def showNotification(id: Int, icon: Int, text: String,
                       channel: Option[ChannelLike] = None) {
    val intent = new Intent(Application.context, classOf[MainActivity])
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    channel foreach { c =>
      intent.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
    }

    val pending = PendingIntent.getActivity(Application.context, id, intent,
      PendingIntent.FLAG_UPDATE_CURRENT)
    val builder = new NotificationCompat.Builder(Application.context)
      .setSmallIcon(icon)
      .setWhen(System.currentTimeMillis())
      .setContentIntent(pending)
      .setContentText(text)
      .setContentTitle(getString(R.string.notif_title))

    val notif = if (channel.isDefined) {
      builder.setPriority(Notification.PRIORITY_HIGH)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setSound(Uri.parse(Settings.get(Settings.NOTIFICATION_SOUND)))
        .setVibrate(if (Settings.get(Settings.NOTIFICATION_VIBRATE))
        Array(0l, 100l, 100l, 100l) else Array(0l)) // required to make heads-up show on lollipop
        .setStyle(new NotificationCompat.BigTextStyle()
        .bigText(text).setBigContentTitle(getString(R.string.notif_title)))
      .build
    } else builder.build

    notif.flags |= Notification.FLAG_AUTO_CANCEL
    channel foreach { c =>
      val cancel = new Intent(ACTION_CANCEL_MENTION)
      cancel.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
      notif.deleteIntent = PendingIntent.getBroadcast(Application.context,
        ACTION_CANCEL_MENTION.hashCode, cancel,
        PendingIntent.FLAG_UPDATE_CURRENT)
    }
    if (Build.VERSION.SDK_INT >= 21)
      notif.headsUpContentView = notif.bigContentView
    nm.notify(id, notif)
  }

  def ping(c: IrcConnection, server: Server) {
    val now = System.currentTimeMillis
    server.currentPing = Some(now)
    c.sendRaw("PING %d" format now)
  }

  val connReceiver = new BroadcastReceiver {
    // convoluted because this broadcast gets spammed  :-/
    // only take a real action on connectivity if net info has changed
    case class NetInfo(typ: String, name: String)
    // this broadcast is sticky...
    var hasRunOnce = false
    var lastConnectedInfo = NetInfo("", "")
    def onReceive(c: Context, intent: Intent) {
      intent.getAction match {
        case ConnectivityManager.CONNECTIVITY_ACTION =>
          val cmm = c.systemService[ConnectivityManager]
          val info = cmm.getActiveNetworkInfo
          val inf = if (info != null)
            NetInfo(info.getTypeName, info.getExtraInfo) else NetInfo("", "")
          val connectivity = !intent.getBooleanExtra(
            ConnectivityManager.EXTRA_NO_CONNECTIVITY, false) &&
            info != null && info.isConnected
          if (_running || (System.currentTimeMillis - lastRunning) < 15000) {
            if (hasRunOnce && (!connectivity || lastConnectedInfo != inf))
              getServers foreach (disconnect(_, None, true))
            if (hasRunOnce && connectivity && lastConnectedInfo != inf) {
              getServers filter { _.autoconnect } foreach connect
              nm.cancel(DISCON_ID)
            }
          }
          lastConnectedInfo = if (connectivity) inf else NetInfo("", "")
          hasRunOnce = true
      }
    }
  }
  val receiver = new BroadcastReceiver {
    def onReceive(c: Context, intent: Intent) {
      val chans = channels.keys.toList.sortWith(_ < _)
      val idx = chans.size + (lastChannel.map { c =>
        chans.indexOf(c)
      } getOrElse 0)
      val tgt = if (chans.isEmpty) 0 else intent.getAction match {
        case ACTION_NEXT_CHANNEL => (idx + 1) % chans.size
        case ACTION_PREV_CHANNEL => (idx - 1) % chans.size
        case ACTION_CANCEL_MENTION =>
          val subject = intent.getStringExtra(EXTRA_SUBJECT)
          Widgets.appenderForSubject(subject) match {
            case Some(c: ChannelLike) =>
              c.newMentions = false
              c.newMessages = false
              ServiceBus.send(ChannelStatusChanged(c))
            case _ =>
          }
          idx % chans.size // FIXME refactor the above
        case _ => idx
      }
      lastChannel = if (chans.size > tgt) Option(chans(tgt)) else None
      nm.notify(RUNNING_ID, runningNotification(runningString))
    }
  }

  def serverMessage(message: String, server: Server) {
    UiBus.run {
      server.add(ServerInfo(message))
    }
  }

  def connectServerTask(server: Server) {
    var state = server.state
    val ircserver = new IrcServer(server.hostname, server.port,
      if (server.sasl) null else server.password, server.ssl)
    val connection = new IrcConnection2
    val negotiator = new CompoundNegotiator(new ServerTimeNegotiator)
    if (server.sasl)
      negotiator.addListener(SaslNegotiator(server.username, server.password,
        result => {
          if (!result) {
            connection.disconnect()
            removeConnection(server)
            state = Server.State.DISCONNECTED
            UiBus.run {
              Toast.makeText(Application.context,
                s"SASL authentication for $server failed",
                Toast.LENGTH_SHORT).show()
            }
          }
      }))
    connection.setCapNegotiatorListener(negotiator)
    connection.setCharset(Charset.forName(Settings.get(Settings.CHARSET)))
    log.i("Connecting to server: " +
      (server.hostname, server.port, server.ssl))
    connection.setServer(ircserver)
    connection.setUsername(server.username, server.realname)
    connection.setNick(server.nickname)

    serverMessage(getString(R.string.server_connecting), server)
    addConnection(server, connection)
    val sslctx = SSLManager.configureSSL(server)
    val listener = new IrcListeners(this)
    connection.setAdvancedListener(listener)
    connection.addServerListener(listener)
    connection.addModeListener(listener)
    connection.addServerEventListener(listener)
    connection.addMessageEventListener(listener)

    try {
      server.currentNick = server.nickname
      if (server.state == Server.State.CONNECTING) {
        connection.connect(sslctx)
        // sasl authentication failure callback will force a disconnect
        if (state != Server.State.DISCONNECTED)
          state = Server.State.CONNECTED
      }
    } catch {
      case e: NickNameException =>
        connection.setNick(server.altnick)
        server.currentNick = server.altnick
        serverMessage(getString(R.string.server_nick_retry), server)
        try {
          if (server.state == Server.State.CONNECTING) {
            connection.connect(sslctx)
            state = Server.State.CONNECTED
          }
        } catch {
          case e: Exception =>
            log.w("Failed to connect, nick exception?", e)
            serverMessage(getString(R.string.server_nick_error), server)
            state = Server.State.DISCONNECTED
            connection.disconnect()
            serverMessage(getString(R.string.server_disconnected), server)
            removeConnection(server)
        }
      case e: Exception =>
        state = Server.State.DISCONNECTED
        removeConnection(server)
        log.e("Unable to connect", e)
        serverMessage(e.getMessage, server)
        try {
          connection.disconnect()
        } catch {
          case ex: Exception =>
            log.e("Exception cleanup failed", ex)
            connection.setConnected(false)
            connection.disconnect()
            state = Server.State.DISCONNECTED
        }
        serverMessage(getString(R.string.server_disconnected), server)
    }

    if (server.state == Server.State.DISCONNECTED)
      connection.disconnect()

    if (state == Server.State.CONNECTED && connection.isConnected)
      ping(connection, server)

    UiBus.run { server.state = state }
  }
  def runningString = getString(R.string.notif_connected_servers,
    connections.size: java.lang.Integer)

  def runningNotification(text: CharSequence): Notification = {
    val intent = new Intent(Application.context, classOf[MainActivity])
    val first = channels.keySet.toSeq sortWith { (a,b) =>
      ChannelLikeComparator.compare(a,b) < 0} headOption

    lastChannel orElse first foreach { c =>
      intent.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
    }
    val pending = PendingIntent.getActivity(Application.context, RUNNING_ID,
      intent, PendingIntent.FLAG_UPDATE_CURRENT)

    val builder = new NotificationCompat.Builder(Application.context)
      .setSmallIcon(R.drawable.ic_notify_mono)
      .setWhen(System.currentTimeMillis())
      .setContentIntent(pending)
      .setContentText(text)
      .setContentTitle(getString(R.string.notif_title))


    lastChannel orElse first map { c =>
      val MAX_LINES = 9

      val chatIntent = new Intent(Application.context, classOf[WidgetChatActivity])
      chatIntent.putExtra(IrcManager.EXTRA_SUBJECT, Widgets.toString(c))
      chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

      val n = builder
        .setContentIntent(PendingIntent.getActivity(Application.context,
        ACTION_QUICK_CHAT.hashCode,
        if (Build.VERSION.SDK_INT < 11) intent else chatIntent,
        PendingIntent.FLAG_UPDATE_CURRENT)).build

      if (Build.VERSION.SDK_INT >= 16 && Settings.get(Settings.RUNNING_NOTIFICATION)) {
        val title = c.name
        val msgs = if (c.messages.filteredMessages.size > 0) {
          TextUtils.concat(
            c.messages.filteredMessages.takeRight(MAX_LINES).map { m =>
              MessageAdapter.formatText(Application.context, m)(c)
            }.flatMap (m => Seq(m, "\n")).init:_*)
        } else {
          getString(R.string.no_messages)
        }

        // TODO account for height of content text view (enable font-sizes)
        val context = Application.context
        val tas = new TextAppearanceSpan(
          context, android.R.style.TextAppearance_Small)
        val paint = new TextPaint
        paint.setTypeface(Typeface.create(tas.getFamily, tas.getTextStyle))
        paint.setTextSize(tas.getTextSize)
        val d = context.getResources.getDimension(
          R.dimen.notification_panel_width)
        val metrics = new DisplayMetrics
        context.systemService[WindowManager].getDefaultDisplay.getMetrics(metrics)
        // 8px margins on notification content
        val margin = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_DIP, 16, context.getResources.getDisplayMetrics)
        // api21 has non-maxwidth notification panels on phones
        val width = math.min(metrics.widthPixels,
          if (d < 0) metrics.widthPixels else d.toInt) - margin

        val layout = new StaticLayout(msgs, paint, width.toInt,
          Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true)
        val lines = layout.getLineCount
        val startOffset = if (lines > MAX_LINES) {
          layout.getLineStart(lines - MAX_LINES)
        } else 0
        n.priority = Notification.PRIORITY_HIGH
        val view = new RemoteViews(
          Application.context.getPackageName, R.layout.notification_content)
        view.setTextViewText(R.id.title, title)
        view.setTextViewText(R.id.content, msgs.subSequence(
          startOffset, msgs.length))
        view.setOnClickPendingIntent(R.id.go_prev,
          PendingIntent.getBroadcast(Application.context,
            ACTION_PREV_CHANNEL.hashCode,
            new Intent(ACTION_PREV_CHANNEL),
            PendingIntent.FLAG_UPDATE_CURRENT))
        view.setOnClickPendingIntent(R.id.widget_input, pending)
        view.setOnClickPendingIntent(R.id.go_next,
          PendingIntent.getBroadcast(Application.context,
            ACTION_NEXT_CHANNEL.hashCode,
            new Intent(ACTION_NEXT_CHANNEL),
            PendingIntent.FLAG_UPDATE_CURRENT))

        n.bigContentView = view
      }

      n
    } getOrElse builder.build
  }
}

case class SaslNegotiator[A](user: String, pass: String, result: Boolean => A)
extends CapNegotiator.Listener {
  override def onNegotiate(capNegotiator: CapNegotiator, packet: IrcPacket) = {
    if (packet.isNumeric) {
      packet.getNumericCommand match {
        case 904 =>
          result(false)
          false // failed: no method, no auth
        case 900 =>
          result(true)
          false // success: logged in
        case 903 =>
          result(true)
          false // success: sasl successful
        case _   => true
      }
    } else {
      if ("AUTHENTICATE +" == packet.getRaw) {
        val buf = ("%s\u0000%s\u0000%s" format (user, user, pass)).getBytes(
          Settings.get(Settings.CHARSET))
        import android.util.Base64
        val auth = Base64.encodeToString(buf, 0, buf.length, 0).trim
        capNegotiator.send("AUTHENTICATE " + auth)
      }
      true
    }
  }

  override def onNegotiateList(capNegotiator: CapNegotiator,
                               features: Array[String]) = {
    if (features.contains("sasl")) {
      capNegotiator.request("sasl")
      true
    } else false
  }

  override def onNegotiateMissing(capNegotiator: CapNegotiator,
                                  feature: String) = "sasl" != feature

  override def onNegotiateFeature(capNegotiator: CapNegotiator,
                                  feature: String) = {
    if ("sasl" == feature) {
      capNegotiator.send("AUTHENTICATE PLAIN")
    }
    true
  }
}
class IrcConnection2 extends IrcConnection {
  override def connect(sslctx: SSLContext) = {
    super.connect(sslctx)
    val thread = getOutput: Thread
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
      override def uncaughtException(thread: Thread, throwable: Throwable) =
        uncaughtExceptionHandler _
    })
  }
  // currently unused
  def uncaughtExceptionHandler(t: Thread, e: Throwable) {
    log.e("Uncaught exception in IRC thread: " + t, e)
    ACRA.getErrorReporter.handleSilentException(e)
    disconnect()
  }
}

object PrintStream
  extends java.io.PrintStream(new java.io.ByteArrayOutputStream) {
  val log = Logcat("sIRC")
  override def println(line: String) = log.d(line)
  override def flush() = ()
}

class StackTrace extends Exception
