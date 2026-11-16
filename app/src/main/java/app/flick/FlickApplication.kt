package app.flick

import android.app.Application

class FlickApplication : Application() {
    val runtime: FlickRuntime by lazy { FlickRuntime(this) }
}
