package com.hanhuy.android.irc

import java.io._
import java.nio.ByteBuffer

import android.content.Context
import android.graphics.{Paint, Typeface}
import android.support.v7.preference.{PreferenceViewHolder, ListPreference, Preference}
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.{TypedValue, AttributeSet}
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget._
import com.hanhuy.android.common._
import iota._

import scala.util.Try

object FontManager {
  private var _fontsByName = Option.empty[Map[String,String]]
  private var _fontsByFile = Option.empty[Map[String,String]]
  def fontsByFile = _fontsByFile getOrElse listFonts._1
  def fontsByName = _fontsByName getOrElse listFonts._2

  private def listFonts = {
    var fonts = Map.empty[String,String]

    val dir = new File("/system/fonts")

    if (dir.exists) {
      val filesOpt = dir.listFiles.?

      filesOpt foreach { files =>
        files foreach { file =>
          val fontname = Try(getFontName(file.getAbsolutePath)).toOption.flatten

          fontname foreach { f =>
            fonts += file.getAbsolutePath -> f
          }
        }
      }
    }

    _fontsByFile = Some(fonts)
    val byName = fonts.map { case (k,v) => v -> k }

    _fontsByName = Some(byName)

    (fonts, byName)
  }

  // https://developer.apple.com/fonts/TrueType-Reference-Manual/RM06/Chap6.html
  // https://developer.apple.com/fonts/TrueType-Reference-Manual/RM06/Chap6name.html
  private val SUPPORTED_VERSIONS = Set(0x74727565, 0x00010000, 0x4f54544f)
  private val SUPPORTED_PLATFORM_IDS = Set(0,1,3)
  def getFontName(fontFilename: String): Option[String] = {
    val file = new RandomAccessFile(fontFilename, "r")
    val version = file.readInt()

    val name = if (SUPPORTED_VERSIONS(version)) {

      val tables = file.readShort()
      file.skipBytes(6)

      val result = (Option.empty[String] /: (0 until tables)) { (r, _) =>
        if (r.isDefined) r else {
          val tag = file.readInt()
          file.skipBytes(4)
          val offset = file.readInt()
          val length = file.readInt()

          if (tag == (0 /: "name") { (a, c) => c.toInt & 0xff | (a << 8) }) {
            val table = Array.ofDim[Byte](length)
            val buf   = ByteBuffer.wrap(table)

            file.seek(offset)
            file.readFully(table)

            val count = buf.getShort(2)
            val string_offset = buf.getShort(4)

            (Option.empty[String] /: (0 until count)) { (a, record) =>
              if (a.isDefined) a else {
                val nameid_offset = record * 12 + 6
                val platformID    = buf.getShort(nameid_offset)
                val nameid_value  = buf.getShort(nameid_offset + 6)

                if (nameid_value == 4 && SUPPORTED_PLATFORM_IDS(platformID)) {
                  val name_length = buf.getShort(nameid_offset + 8)
                  val name_offset = buf.getShort(nameid_offset + 10) + string_offset

                  if (name_offset >= 0 && name_offset + name_length < table.length)
                    Some(new String(table, name_offset, name_length))
                  else a
                } else a
              }
            }
          } else r
        }
      }
      result
    } else None
    file.close()
    name
  }
}
class FontSizePreference(c: Context, attrs: AttributeSet)
extends Preference(c, attrs) with HasContext {
  override def context = c

  def progressChanged[A](fn: (Int, Boolean) => A) = new OnSeekBarChangeListener {
    override def onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) =
      fn(progress, fromUser)
    override def onStopTrackingTouch(seekBar: SeekBar) = ()
    override def onStartTrackingTouch(seekBar: SeekBar) = ()
  }
  setLayoutResource(R.layout.preference_font_size)

  val fontNameKey = styledAttrs(attrs, R.styleable.FontSizePreference,
    _.getString(R.styleable.FontSizePreference_fontNameKey))


  override def onBindViewHolder(holder: PreferenceViewHolder) = {
    val typeface = for {
      k <- fontNameKey.?
      n <- getSharedPreferences.getString(k, null).?
      t  = Typeface.createFromFile(n)
    } yield t
    val summary = holder.findViewById(android.R.id.summary).asInstanceOf[TextView]
    val defaultSize = (summary.getTextSize /
      getContext.getResources.getDisplayMetrics.scaledDensity).toInt
    summary.setText("%d sp" format getPersistedInt(defaultSize))
    summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, getPersistedInt(defaultSize))
    summary.setIncludeFontPadding(false)
    val seekbar = holder.findViewById(R.id.font_size).asInstanceOf[SeekBar]
    seekbar.setProgress(math.max(0, getPersistedInt(defaultSize) - 4))
    seekbar.setOnSeekBarChangeListener(progressChanged { (p, touch) => ()
      if (touch) {
        val size = p + 4
        persistInt(size)
        summary.setText("%d sp" format size)
        summary.setTextSize( TypedValue.COMPLEX_UNIT_SP, size)
      }
    })
    typeface foreach summary.setTypeface
  }
}

class TypefaceSpan(typeface: Typeface) extends MetricAffectingSpan {

  override def updateDrawState(drawState: TextPaint) {
    update(drawState)
  }

  override def updateMeasureState(paint: TextPaint) {
    update(paint)
  }

  def update(paint: Paint) {
    val oldTypeface = paint.getTypeface
    val oldStyle    = oldTypeface.?.fold(0)(_.getStyle)
    val fakeStyle   = oldStyle & ~typeface.getStyle
    if ((fakeStyle & Typeface.BOLD)   != 0) paint.setFakeBoldText(true)
    if ((fakeStyle & Typeface.ITALIC) != 0) paint.setTextSkewX(-0.25f)
    paint.setTypeface(typeface)
  }
}

class FontNamePreference(c: Context, attrs: AttributeSet)
extends ListPreference(c, attrs) {
  import SpannedGenerator._
  val (names, paths) = FontManager.fontsByName.toList.sortBy(_._1).unzip

  val entries = names map { n =>
    "%1" formatSpans span(
      new TypefaceSpan(Typeface.createFromFile(FontManager.fontsByName(n))), n)
  }
  setEntries(entries.toArray: Array[CharSequence])
  setEntryValues(paths.toArray: Array[CharSequence])

  override def setValue(value: String) = {
    super.setValue(value)
    setSummary(getEntry)
  }

  override def getSummary = getEntry
}
