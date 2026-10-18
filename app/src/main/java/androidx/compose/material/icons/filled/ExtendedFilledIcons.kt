package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

private fun buildSimpleIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector {
    return materialIcon(name = name) {
        materialPath(pathBuilder = block)
    }
}

val Icons.Filled.AdminPanelSettings: ImageVector by lazy {
    buildSimpleIcon("Filled.AdminPanelSettings") {
        moveTo(12.0f, 1.0f); lineTo(3.0f, 5.0f); verticalLineTo(11.0f)
        curveTo(3.0f, 16.55f, 6.84f, 21.74f, 12.0f, 23.0f)
        curveTo(17.16f, 21.74f, 21.0f, 16.55f, 21.0f, 11.0f)
        verticalLineTo(5.0f); close()
    }
}

val Icons.Filled.Archive: ImageVector by lazy {
    buildSimpleIcon("Filled.Archive") {
        moveTo(20.54f, 5.23f); lineTo(19.15f, 3.55f); horizontalLineTo(4.85f); lineTo(3.46f, 5.23f)
        verticalLineTo(19.0f); horizontalLineTo(20.54f); close()
        moveTo(12.0f, 17.5f); lineTo(6.5f, 12.0f); horizontalLineTo(10.0f); verticalLineTo(10.0f)
        horizontalLineTo(14.0f); verticalLineTo(12.0f); horizontalLineTo(17.5f); close()
    }
}

val Icons.Filled.AudioFile: ImageVector by lazy {
    buildSimpleIcon("Filled.AudioFile") {
        moveTo(14.0f, 2.0f); horizontalLineTo(6.0f); verticalLineTo(22.0f); horizontalLineTo(18.0f)
        verticalLineTo(8.0f); close()
        moveTo(16.0f, 13.0f); horizontalLineTo(13.0f); verticalLineTo(17.5f)
        curveTo(13.0f, 18.88f, 11.88f, 20.0f, 10.5f, 20.0f)
        curveTo(9.12f, 20.0f, 8.0f, 18.88f, 8.0f, 17.5f)
        curveTo(8.0f, 16.12f, 9.12f, 15.0f, 10.5f, 15.0f)
        verticalLineTo(11.0f); horizontalLineTo(16.0f); close()
    }
}

val Icons.Filled.BatteryChargingFull: ImageVector by lazy {
    buildSimpleIcon("Filled.BatteryChargingFull") {
        moveTo(15.67f, 4.0f); horizontalLineTo(14.0f); verticalLineTo(2.0f); horizontalLineTo(10.0f)
        verticalLineTo(4.0f); horizontalLineTo(8.33f); verticalLineTo(22.0f); horizontalLineTo(15.67f); close()
        moveTo(11.0f, 20.0f); verticalLineTo(14.5f); horizontalLineTo(9.0f); lineTo(13.0f, 7.0f)
        verticalLineTo(12.5f); horizontalLineTo(15.0f); close()
    }
}

val Icons.Filled.CameraAlt: ImageVector by lazy {
    buildSimpleIcon("Filled.CameraAlt") {
        moveTo(9.0f, 2.0f); lineTo(7.17f, 4.0f); horizontalLineTo(4.0f); verticalLineTo(20.0f)
        horizontalLineTo(20.0f); verticalLineTo(4.0f); horizontalLineTo(16.83f); lineTo(15.0f, 2.0f); close()
        moveTo(12.0f, 17.0f); curveTo(9.24f, 17.0f, 7.0f, 14.76f, 7.0f, 12.0f)
        curveTo(7.0f, 9.24f, 9.24f, 7.0f, 12.0f, 7.0f)
        curveTo(14.76f, 7.0f, 17.0f, 9.24f, 17.0f, 12.0f)
        curveTo(17.0f, 14.76f, 14.76f, 17.0f, 12.0f, 17.0f); close()
    }
}

val Icons.Filled.CastConnected: ImageVector by lazy {
    buildSimpleIcon("Filled.CastConnected") {
        moveTo(21.0f, 3.0f); horizontalLineTo(3.0f); verticalLineTo(8.0f); horizontalLineTo(5.0f)
        verticalLineTo(5.0f); horizontalLineTo(19.0f); verticalLineTo(19.0f); horizontalLineTo(14.0f)
        verticalLineTo(21.0f); horizontalLineTo(21.0f); close()
        moveTo(1.0f, 18.0f); verticalLineTo(21.0f); horizontalLineTo(4.0f); close()
        moveTo(1.0f, 14.0f); verticalLineTo(16.0f); horizontalLineTo(6.0f); close()
    }
}

val Icons.Filled.ChevronLeft: ImageVector by lazy {
    buildSimpleIcon("Filled.ChevronLeft") {
        moveTo(15.41f, 7.41f); lineTo(14.0f, 6.0f); lineTo(8.0f, 12.0f)
        lineTo(14.0f, 18.0f); lineTo(15.41f, 16.59f); lineTo(10.83f, 12.0f); close()
    }
}

val Icons.Filled.Cloud: ImageVector by lazy {
    buildSimpleIcon("Filled.Cloud") {
        moveTo(19.35f, 10.04f)
        curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
        curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
        curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
        curveTo(0.0f, 17.31f, 2.69f, 20.0f, 6.0f, 20.0f)
        horizontalLineTo(19.0f)
        curveTo(21.76f, 20.0f, 24.0f, 17.76f, 24.0f, 15.0f)
        curveTo(24.0f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f); close()
    }
}

val Icons.Filled.CloudDone: ImageVector by lazy {
    buildSimpleIcon("Filled.CloudDone") {
        moveTo(19.35f, 10.04f)
        curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
        curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
        curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
        curveTo(0.0f, 17.31f, 2.69f, 20.0f, 6.0f, 20.0f)
        horizontalLineTo(19.0f)
        curveTo(21.76f, 20.0f, 24.0f, 17.76f, 24.0f, 15.0f)
        curveTo(24.0f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f); close()
        moveTo(10.0f, 17.0f); lineTo(6.5f, 13.5f); lineTo(7.91f, 12.09f)
        lineTo(10.0f, 14.17f); lineTo(15.18f, 9.0f); lineTo(16.59f, 10.41f); close()
    }
}

val Icons.Filled.CloudOff: ImageVector by lazy {
    buildSimpleIcon("Filled.CloudOff") {
        moveTo(3.0f, 5.27f); lineTo(19.73f, 22.0f); lineTo(21.0f, 20.73f); lineTo(4.27f, 4.0f); close()
        moveTo(19.35f, 10.04f)
        curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
        horizontalLineTo(6.0f); verticalLineTo(20.0f); horizontalLineTo(17.73f); close()
    }
}

val Icons.Filled.CloudUpload: ImageVector by lazy {
    buildSimpleIcon("Filled.CloudUpload") {
        moveTo(19.35f, 10.04f)
        curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
        curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
        curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
        curveTo(0.0f, 17.31f, 2.69f, 20.0f, 6.0f, 20.0f)
        horizontalLineTo(19.0f)
        curveTo(21.76f, 20.0f, 24.0f, 17.76f, 24.0f, 15.0f)
        curveTo(24.0f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f); close()
        moveTo(14.0f, 13.0f); verticalLineTo(17.0f); horizontalLineTo(10.0f); verticalLineTo(13.0f)
        horizontalLineTo(7.0f); lineTo(12.0f, 8.0f); lineTo(17.0f, 13.0f); close()
    }
}

val Icons.Filled.DeleteSweep: ImageVector by lazy {
    buildSimpleIcon("Filled.DeleteSweep") {
        moveTo(15.0f, 16.0f); horizontalLineTo(19.0f); verticalLineTo(18.0f); horizontalLineTo(15.0f); close()
        moveTo(15.0f, 8.0f); horizontalLineTo(22.0f); verticalLineTo(10.0f); horizontalLineTo(15.0f); close()
        moveTo(15.0f, 12.0f); horizontalLineTo(21.0f); verticalLineTo(14.0f); horizontalLineTo(15.0f); close()
        moveTo(3.0f, 18.0f); verticalLineTo(8.0f); horizontalLineTo(13.0f); verticalLineTo(18.0f); close()
        moveTo(14.0f, 5.0f); horizontalLineTo(11.0f); lineTo(10.0f, 4.0f); horizontalLineTo(6.0f)
        lineTo(5.0f, 5.0f); horizontalLineTo(2.0f); verticalLineTo(7.0f); horizontalLineTo(14.0f); close()
    }
}

val Icons.Filled.Description: ImageVector by lazy {
    buildSimpleIcon("Filled.Description") {
        moveTo(14.0f, 2.0f); horizontalLineTo(6.0f); verticalLineTo(22.0f); horizontalLineTo(18.0f)
        verticalLineTo(8.0f); close()
        moveTo(16.0f, 18.0f); horizontalLineTo(8.0f); verticalLineTo(16.0f); horizontalLineTo(16.0f); close()
        moveTo(16.0f, 14.0f); horizontalLineTo(8.0f); verticalLineTo(12.0f); horizontalLineTo(16.0f); close()
    }
}

val Icons.Filled.Dns: ImageVector by lazy {
    buildSimpleIcon("Filled.Dns") {
        moveTo(20.0f, 13.0f); horizontalLineTo(4.0f); verticalLineTo(20.0f); horizontalLineTo(20.0f); close()
        moveTo(7.0f, 18.0f); horizontalLineTo(5.0f); verticalLineTo(15.0f); horizontalLineTo(7.0f); close()
        moveTo(20.0f, 3.0f); horizontalLineTo(4.0f); verticalLineTo(10.0f); horizontalLineTo(20.0f); close()
        moveTo(7.0f, 8.0f); horizontalLineTo(5.0f); verticalLineTo(5.0f); horizontalLineTo(7.0f); close()
    }
}

val Icons.Filled.Download: ImageVector by lazy {
    buildSimpleIcon("Filled.Download") {
        moveTo(5.0f, 20.0f); horizontalLineTo(19.0f); verticalLineTo(18.0f); horizontalLineTo(5.0f); close()
        moveTo(19.0f, 9.0f); horizontalLineTo(15.0f); verticalLineTo(3.0f); horizontalLineTo(9.0f)
        verticalLineTo(9.0f); horizontalLineTo(5.0f); lineTo(12.0f, 16.0f); close()
    }
}

val Icons.Filled.Folder: ImageVector by lazy {
    buildSimpleIcon("Filled.Folder") {
        moveTo(10.0f, 4.0f); horizontalLineTo(4.0f); verticalLineTo(20.0f); horizontalLineTo(20.0f)
        verticalLineTo(6.0f); horizontalLineTo(12.0f); close()
    }
}

val Icons.Filled.GraphicEq: ImageVector by lazy {
    buildSimpleIcon("Filled.GraphicEq") {
        moveTo(7.0f, 18.0f); horizontalLineTo(9.0f); verticalLineTo(6.0f); horizontalLineTo(7.0f); close()
        moveTo(11.0f, 22.0f); horizontalLineTo(13.0f); verticalLineTo(2.0f); horizontalLineTo(11.0f); close()
        moveTo(3.0f, 14.0f); horizontalLineTo(5.0f); verticalLineTo(10.0f); horizontalLineTo(3.0f); close()
        moveTo(15.0f, 18.0f); horizontalLineTo(17.0f); verticalLineTo(6.0f); horizontalLineTo(15.0f); close()
        moveTo(19.0f, 10.0f); verticalLineTo(14.0f); horizontalLineTo(21.0f); verticalLineTo(10.0f); close()
    }
}

val Icons.Filled.Image: ImageVector by lazy {
    buildSimpleIcon("Filled.Image") {
        moveTo(21.0f, 19.0f); verticalLineTo(5.0f); horizontalLineTo(3.0f); verticalLineTo(19.0f); close()
        moveTo(8.5f, 13.5f); lineTo(11.0f, 16.51f); lineTo(14.5f, 12.0f); lineTo(19.0f, 18.0f)
        horizontalLineTo(5.0f); close()
    }
}

val Icons.Filled.Key: ImageVector by lazy {
    buildSimpleIcon("Filled.Key") {
        moveTo(12.65f, 10.0f)
        curveTo(11.83f, 7.67f, 9.61f, 6.0f, 7.0f, 6.0f)
        curveTo(3.69f, 6.0f, 1.0f, 8.69f, 1.0f, 12.0f)
        curveTo(1.0f, 15.31f, 3.69f, 18.0f, 7.0f, 18.0f)
        curveTo(9.61f, 18.0f, 11.83f, 16.33f, 12.65f, 14.0f)
        horizontalLineTo(17.0f); verticalLineTo(18.0f); horizontalLineTo(21.0f); verticalLineTo(14.0f)
        horizontalLineTo(23.0f); verticalLineTo(10.0f); close()
    }
}

val Icons.Filled.NotificationsActive: ImageVector by lazy {
    buildSimpleIcon("Filled.NotificationsActive") {
        moveTo(12.0f, 22.0f); curveTo(13.1f, 22.0f, 14.0f, 21.1f, 14.0f, 20.0f); horizontalLineTo(10.0f)
        curveTo(10.0f, 21.1f, 10.89f, 22.0f, 12.0f, 22.0f); close()
        moveTo(18.0f, 16.0f); verticalLineTo(11.0f)
        curveTo(18.0f, 7.93f, 16.36f, 5.36f, 13.5f, 4.68f); verticalLineTo(4.0f)
        curveTo(13.5f, 3.17f, 12.83f, 2.5f, 12.0f, 2.5f)
        curveTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4.0f); verticalLineTo(4.68f)
        curveTo(7.63f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f); verticalLineTo(16.0f)
        lineTo(4.0f, 18.0f); verticalLineTo(19.0f); horizontalLineTo(20.0f); verticalLineTo(18.0f); close()
    }
}

val Icons.Filled.Pause: ImageVector by lazy {
    buildSimpleIcon("Filled.Pause") {
        moveTo(6.0f, 19.0f); horizontalLineTo(10.0f); verticalLineTo(5.0f); horizontalLineTo(6.0f); close()
        moveTo(14.0f, 5.0f); verticalLineTo(19.0f); horizontalLineTo(18.0f); verticalLineTo(5.0f); close()
    }
}

val Icons.Filled.PersonAdd: ImageVector by lazy {
    buildSimpleIcon("Filled.PersonAdd") {
        moveTo(15.0f, 12.0f); curveTo(17.21f, 12.0f, 19.0f, 10.21f, 19.0f, 8.0f)
        curveTo(19.0f, 5.79f, 17.21f, 4.0f, 15.0f, 4.0f)
        curveTo(12.79f, 4.0f, 11.0f, 5.79f, 11.0f, 8.0f)
        curveTo(11.0f, 10.21f, 12.79f, 12.0f, 15.0f, 12.0f); close()
        moveTo(6.0f, 10.0f); verticalLineTo(7.0f); horizontalLineTo(4.0f); verticalLineTo(10.0f)
        horizontalLineTo(1.0f); verticalLineTo(12.0f); horizontalLineTo(4.0f); verticalLineTo(15.0f)
        horizontalLineTo(6.0f); verticalLineTo(12.0f); horizontalLineTo(9.0f); verticalLineTo(10.0f); close()
        moveTo(15.0f, 14.0f); curveTo(12.33f, 14.0f, 7.0f, 15.34f, 7.0f, 18.0f); verticalLineTo(20.0f)
        horizontalLineTo(23.0f); verticalLineTo(18.0f); curveTo(23.0f, 15.34f, 17.67f, 14.0f, 15.0f, 14.0f); close()
    }
}

val Icons.Filled.PlayCircleFilled: ImageVector by lazy {
    buildSimpleIcon("Filled.PlayCircleFilled") {
        moveTo(12.0f, 2.0f); curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
        curveTo(2.0f, 17.52f, 6.48f, 22.0f, 12.0f, 22.0f)
        curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
        curveTo(22.0f, 6.48f, 17.52f, 2.0f, 12.0f, 2.0f); close()
        moveTo(10.0f, 16.5f); verticalLineTo(7.5f); lineTo(16.0f, 12.0f); close()
    }
}

val Icons.Filled.PowerSettingsNew: ImageVector by lazy {
    buildSimpleIcon("Filled.PowerSettingsNew") {
        moveTo(13.0f, 3.0f); horizontalLineTo(11.0f); verticalLineTo(13.0f); horizontalLineTo(13.0f); close()
        moveTo(17.83f, 5.17f); lineTo(16.41f, 6.59f)
        curveTo(17.99f, 7.86f, 19.0f, 9.81f, 19.0f, 12.0f)
        curveTo(19.0f, 15.87f, 15.87f, 19.0f, 12.0f, 19.0f)
        curveTo(8.13f, 19.0f, 5.0f, 15.87f, 5.0f, 12.0f)
        curveTo(5.0f, 9.81f, 6.01f, 7.86f, 7.58f, 6.58f); lineTo(6.17f, 5.17f)
        curveTo(4.23f, 6.82f, 3.0f, 9.26f, 3.0f, 12.0f)
        curveTo(3.0f, 16.97f, 7.03f, 21.0f, 12.0f, 21.0f)
        curveTo(16.97f, 21.0f, 21.0f, 16.97f, 21.0f, 12.0f)
        curveTo(21.0f, 9.26f, 19.77f, 6.82f, 17.83f, 5.17f); close()
    }
}

val Icons.Filled.Router: ImageVector by lazy {
    buildSimpleIcon("Filled.Router") {
        moveTo(16.0f, 13.0f); horizontalLineTo(8.0f); verticalLineTo(9.0f); horizontalLineTo(6.0f)
        verticalLineTo(13.0f); horizontalLineTo(4.0f); verticalLineTo(21.0f); horizontalLineTo(20.0f)
        verticalLineTo(13.0f); close()
        moveTo(8.0f, 18.0f); horizontalLineTo(6.0f); verticalLineTo(16.0f); horizontalLineTo(8.0f); close()
    }
}

val Icons.Filled.ScreenShare: ImageVector by lazy {
    buildSimpleIcon("Filled.ScreenShare") {
        moveTo(20.0f, 18.0f); verticalLineTo(6.0f); horizontalLineTo(4.0f); verticalLineTo(18.0f)
        horizontalLineTo(0.0f); verticalLineTo(20.0f); horizontalLineTo(24.0f); verticalLineTo(18.0f); close()
        moveTo(13.0f, 14.47f); verticalLineTo(12.28f)
        curveTo(10.22f, 12.28f, 8.39f, 13.13f, 7.0f, 15.0f)
        curveTo(7.56f, 12.33f, 9.11f, 9.67f, 13.0f, 9.13f); verticalLineTo(7.0f)
        lineTo(17.0f, 10.73f); close()
    }
}

val Icons.Filled.SdStorage: ImageVector by lazy {
    buildSimpleIcon("Filled.SdStorage") {
        moveTo(18.0f, 2.0f); horizontalLineTo(10.0f); lineTo(4.02f, 8.0f); lineTo(4.0f, 20.0f)
        horizontalLineTo(18.0f); close()
        moveTo(12.0f, 8.0f); horizontalLineTo(10.0f); verticalLineTo(4.0f); horizontalLineTo(12.0f); close()
        moveTo(15.0f, 8.0f); horizontalLineTo(13.0f); verticalLineTo(4.0f); horizontalLineTo(15.0f); close()
        moveTo(18.0f, 8.0f); horizontalLineTo(16.0f); verticalLineTo(4.0f); horizontalLineTo(18.0f); close()
    }
}

val Icons.Filled.Security: ImageVector by lazy {
    buildSimpleIcon("Filled.Security") {
        moveTo(12.0f, 1.0f); lineTo(3.0f, 5.0f); verticalLineTo(11.0f)
        curveTo(3.0f, 16.55f, 6.84f, 21.74f, 12.0f, 23.0f)
        curveTo(17.16f, 21.74f, 21.0f, 16.55f, 21.0f, 11.0f)
        verticalLineTo(5.0f); close()
        moveTo(12.0f, 11.99f); horizontalLineTo(19.0f)
        curveTo(18.47f, 16.11f, 15.72f, 19.78f, 12.0f, 20.93f); verticalLineTo(12.0f)
        horizontalLineTo(5.0f); verticalLineTo(6.3f); lineTo(12.0f, 3.19f); close()
    }
}

val Icons.Filled.Stop: ImageVector by lazy {
    buildSimpleIcon("Filled.Stop") {
        moveTo(6.0f, 6.0f); horizontalLineTo(18.0f); verticalLineTo(18.0f); horizontalLineTo(6.0f); close()
    }
}

val Icons.Filled.Storage: ImageVector by lazy {
    buildSimpleIcon("Filled.Storage") {
        moveTo(2.0f, 20.0f); horizontalLineTo(22.0f); verticalLineTo(16.0f); horizontalLineTo(2.0f); close()
        moveTo(2.0f, 4.0f); verticalLineTo(8.0f); horizontalLineTo(22.0f); verticalLineTo(4.0f); close()
        moveTo(2.0f, 14.0f); horizontalLineTo(22.0f); verticalLineTo(10.0f); horizontalLineTo(2.0f); close()
    }
}

val Icons.Filled.Terminal: ImageVector by lazy {
    buildSimpleIcon("Filled.Terminal") {
        moveTo(20.0f, 4.0f); horizontalLineTo(4.0f); verticalLineTo(20.0f); horizontalLineTo(20.0f); close()
        moveTo(18.0f, 17.0f); horizontalLineTo(12.0f); verticalLineTo(15.0f); horizontalLineTo(18.0f); close()
        moveTo(7.5f, 17.0f); lineTo(6.09f, 15.59f); lineTo(9.67f, 12.0f); lineTo(6.09f, 8.41f)
        lineTo(7.5f, 7.0f); lineTo(12.5f, 12.0f); close()
    }
}

val Icons.Filled.VideoFile: ImageVector by lazy {
    buildSimpleIcon("Filled.VideoFile") {
        moveTo(14.0f, 2.0f); horizontalLineTo(6.0f); verticalLineTo(22.0f); horizontalLineTo(18.0f)
        verticalLineTo(8.0f); close()
        moveTo(14.0f, 13.0f); lineTo(16.0f, 11.94f); verticalLineTo(16.06f); lineTo(14.0f, 15.0f)
        verticalLineTo(17.0f); horizontalLineTo(8.0f); verticalLineTo(11.0f); horizontalLineTo(14.0f); close()
    }
}

val Icons.Filled.Videocam: ImageVector by lazy {
    buildSimpleIcon("Filled.Videocam") {
        moveTo(17.0f, 10.5f); verticalLineTo(7.0f); horizontalLineTo(3.0f); verticalLineTo(17.0f)
        horizontalLineTo(17.0f); verticalLineTo(13.5f); lineTo(21.0f, 17.5f); verticalLineTo(6.5f); close()
    }
}

val Icons.Filled.Visibility: ImageVector by lazy {
    buildSimpleIcon("Filled.Visibility") {
        moveTo(12.0f, 4.5f)
        curveTo(7.0f, 4.5f, 2.73f, 7.61f, 1.0f, 12.0f)
        curveTo(2.73f, 16.39f, 7.0f, 19.5f, 12.0f, 19.5f)
        curveTo(17.0f, 19.5f, 21.27f, 16.39f, 23.0f, 12.0f)
        curveTo(21.27f, 7.61f, 17.0f, 4.5f, 12.0f, 4.5f); close()
        moveTo(12.0f, 17.0f); curveTo(9.24f, 17.0f, 7.0f, 14.76f, 7.0f, 12.0f)
        curveTo(7.0f, 9.24f, 9.24f, 7.0f, 12.0f, 7.0f)
        curveTo(14.76f, 7.0f, 17.0f, 9.24f, 17.0f, 12.0f)
        curveTo(17.0f, 14.76f, 14.76f, 17.0f, 12.0f, 17.0f); close()
    }
}

val Icons.Filled.VisibilityOff: ImageVector by lazy {
    buildSimpleIcon("Filled.VisibilityOff") {
        moveTo(2.0f, 4.27f); lineTo(19.73f, 22.0f); lineTo(21.0f, 20.73f); lineTo(3.27f, 3.0f); close()
        moveTo(12.0f, 4.5f)
        curveTo(17.0f, 4.5f, 21.27f, 7.61f, 23.0f, 12.0f)
        curveTo(22.18f, 14.08f, 20.79f, 15.88f, 19.0f, 17.19f)
        lineTo(6.53f, 4.71f); close()
    }
}

val Icons.Filled.VpnKey: ImageVector by lazy {
    buildSimpleIcon("Filled.VpnKey") {
        moveTo(12.65f, 10.0f)
        curveTo(11.83f, 7.67f, 9.61f, 6.0f, 7.0f, 6.0f)
        curveTo(3.69f, 6.0f, 1.0f, 8.69f, 1.0f, 12.0f)
        curveTo(1.0f, 15.31f, 3.69f, 18.0f, 7.0f, 18.0f)
        curveTo(9.61f, 18.0f, 11.83f, 16.33f, 12.65f, 14.0f)
        horizontalLineTo(17.0f); verticalLineTo(18.0f); horizontalLineTo(21.0f); verticalLineTo(14.0f)
        horizontalLineTo(23.0f); verticalLineTo(10.0f); close()
    }
}

val Icons.Filled.WarningAmber: ImageVector by lazy {
    buildSimpleIcon("Filled.WarningAmber") {
        moveTo(12.0f, 5.99f); lineTo(19.53f, 19.0f); horizontalLineTo(4.47f); close()
        moveTo(12.0f, 2.0f); lineTo(1.0f, 21.0f); horizontalLineTo(23.0f); close()
        moveTo(13.0f, 16.0f); horizontalLineTo(11.0f); verticalLineTo(18.0f); horizontalLineTo(13.0f); close()
        moveTo(13.0f, 10.0f); horizontalLineTo(11.0f); verticalLineTo(14.0f); horizontalLineTo(13.0f); close()
    }
}

val Icons.Filled.Wifi: ImageVector by lazy {
    buildSimpleIcon("Filled.Wifi") {
        moveTo(1.0f, 9.0f); lineTo(3.0f, 11.0f)
        curveTo(7.97f, 6.03f, 16.03f, 6.03f, 21.0f, 11.0f); lineTo(23.0f, 9.0f)
        curveTo(16.93f, 2.93f, 7.08f, 2.93f, 1.0f, 9.0f); close()
        moveTo(9.0f, 17.0f); lineTo(12.0f, 20.0f); lineTo(15.0f, 17.0f)
        curveTo(13.35f, 15.34f, 10.66f, 15.34f, 9.0f, 17.0f); close()
        moveTo(5.0f, 13.0f); lineTo(7.0f, 15.0f)
        curveTo(9.76f, 12.24f, 14.24f, 12.24f, 17.0f, 15.0f); lineTo(19.0f, 13.0f)
        curveTo(15.14f, 9.14f, 8.87f, 9.14f, 5.0f, 13.0f); close()
    }
}
