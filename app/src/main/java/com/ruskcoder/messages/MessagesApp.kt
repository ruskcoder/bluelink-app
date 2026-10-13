package com.ruskcoder.messages

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

class MessagesApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Counts the app's activities in and out, which is how the lock knows the app was left.
        registerActivityLifecycleCallbacks(container.appLock)
    }

    /**
     * Coil decodes images out of the box but not video, so a video attachment's still would come
     * back as a decode failure and the bubble would fall through to its placeholder icon. Adding
     * the frame decoder is what makes a sent clip look like the thing it is.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
