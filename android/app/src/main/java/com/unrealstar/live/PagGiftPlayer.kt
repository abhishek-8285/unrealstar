package com.unrealstar.live

import android.content.Context
import android.view.View
import org.libpag.PAGFile
import org.libpag.PAGView

/**
 * Full-screen luxury gift effect player (Phase 1 next step).
 * Loads a .pag file from assets (e.g. taj_palace.pag) into the supplied
 * PAGView and plays once. No-op when the asset is absent so rooms work
 * before the art bundle lands.
 */
object PagGiftPlayer {
    fun playOnce(context: Context, pagView: PAGView, assetName: String, onEnd: (() -> Unit)? = null) {
        try {
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            val file = PAGFile.Load(bytes)
                ?: run { pagView.visibility = View.GONE; return }
            pagView.setComposition(file)
            pagView.setRepeatCount(1)
            pagView.addListener(object : PAGView.PAGViewListener {
                override fun onAnimationStart(view: PAGView) { view.visibility = View.VISIBLE }
                override fun onAnimationEnd(view: PAGView) {
                    view.visibility = View.GONE
                    onEnd?.invoke()
                }
                override fun onAnimationCancel(view: PAGView) { view.visibility = View.GONE }
                override fun onAnimationRepeat(view: PAGView) {}
                override fun onAnimationUpdate(view: PAGView) {}
            })
            pagView.play()
        } catch (_: Exception) {
            pagView.visibility = View.GONE
        }
    }
}
