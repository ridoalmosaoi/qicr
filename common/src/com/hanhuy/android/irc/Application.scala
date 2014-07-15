package com.hanhuy.android.irc

import android.content.Intent
import org.acra.ACRA
import org.acra.annotation.ReportsCrashes

/**
 * @author pfnguyen
 */
@ReportsCrashes(formKey = "",
  formUri = "http://hanhuy-acra.appspot.com/api/crashreport")
class Application extends android.app.Application {
  Application._instance = this

  override def onCreate() {
    super.onCreate()
    ACRA.init(this)

    startService(new Intent(this, classOf[LifecycleService]))
  }
}

object Application {
  // this should absolutely always be non-null
  private var _instance: Application = null

  def context = _instance
}
