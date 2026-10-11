package com.camoron.core.common

import android.view.Surface
import com.camoron.domain.repository.PreviewTarget

/** Android [Surface] the camera preview is streamed into. Width and height are the buffer size. */
class SurfacePreviewTarget(
    val surface: Surface,
    override val width: Int,
    override val height: Int,
) : PreviewTarget
