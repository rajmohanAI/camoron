package com.camoron.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.camoron.app.R

/** The five tabs of the spotlight bar, in order, and the screen each one opens. */
enum class TopLevelDestination(
    val icon: ImageVector,
    @StringRes val label: Int,
    val route: Any,
) {
    CAMERA(Icons.Outlined.PhotoCamera, R.string.tab_camera, CameraRoute),
    GALLERY(Icons.Outlined.PhotoLibrary, R.string.tab_gallery, GalleryRoute),
    EDIT(Icons.Outlined.Edit, R.string.tab_edit, EditHomeRoute),
    AI_TOOLS(Icons.Outlined.AutoAwesome, R.string.tab_ai_tools, AiToolsRoute),

    /** Camoron has no accounts; Profile is where the app's settings live. */
    PROFILE(Icons.Outlined.Person, R.string.tab_profile, SettingsRoute),
    ;

    companion object {
        /** The tab whose own screen [destination] is, or null for screens inside a tab (viewer, editor, ...). */
        fun of(destination: NavDestination?): TopLevelDestination? = when {
            destination == null -> null
            destination.hasRoute<CameraRoute>() -> CAMERA
            destination.hasRoute<GalleryRoute>() -> GALLERY
            destination.hasRoute<EditHomeRoute>() -> EDIT
            destination.hasRoute<AiToolsRoute>() -> AI_TOOLS
            destination.hasRoute<SettingsRoute>() -> PROFILE
            else -> null
        }
    }
}
