package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppMode
import com.example.model.HomeNodeInfo
import com.example.model.RemoteFileItem
import com.example.model.ServerLog
import com.example.model.TransferProgress
import java.io.File

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

data class SystemPermissionsState(
    val hasStorageAccess: Boolean,
    val hasNotificationPermission: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val isNotificationSkipped: Boolean = false
) {
    val allGranted: Boolean
        get() = hasStorageAccess && isIgnoringBatteryOptimizations
}

// =============================================================================
// 1. AUTH SCREEN (Unified Cloud Sign-In & Sign-Up Screen: Email/Password + Google)
// =============================================================================

@Composable
fun AuthScreen(
    isLoading: Boolean,
    statusMessage: String? = null,
    errorMessage: String?,
    onEmailSignInClick: (email: String, password: String) -> Unit = { _, _ -> },
    onEmailSignUpClick: (email: String, password: String, confirmPassword: String) -> Unit = { _, _, _ -> },
    onResetPasswordClick: (email: String) -> Unit = {},
    onGoogleSignInClick: () -> Unit,
    onDismissError: () -> Unit
) {
    var isSignUpMode by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var isConfirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 8.dp,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = "شعار السحابة المنزلية الذكية",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Smart Home Cloud",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "نظام السحابة المنزلية الذكية للربط التلقائي بين هاتف المنزل الخادم وهاتف التحكم عن بُعد بدون إدخال عناوين IP يدوياً",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Status Banner (e.g., Password Reset Link Sent)
                    AnimatedVisibility(visible = !statusMessage.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "إشعار نجاح",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = statusMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissError) {
                                    Text("إغلاق")
                                }
                            }
                        }
                    }

                    // Error Banner
                    AnimatedVisibility(visible = !errorMessage.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = "تنبيه خطأ",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissError) {
                                    Text("إغلاق")
                                }
                            }
                        }
                    }

                    ElevatedCard(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Tabs: Sign In vs Create Account
                            TabRow(
                                selectedTabIndex = if (isSignUpMode) 1 else 0,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                            ) {
                                Tab(
                                    selected = !isSignUpMode,
                                    onClick = {
                                        isSignUpMode = false
                                        onDismissError()
                                    },
                                    modifier = Modifier.testTag("tab_sign_in"),
                                    text = {
                                        Text(
                                            text = "تسجيل الدخول",
                                            fontWeight = if (!isSignUpMode) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.VpnKey,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                                Tab(
                                    selected = isSignUpMode,
                                    onClick = {
                                        isSignUpMode = true
                                        onDismissError()
                                    },
                                    modifier = Modifier.testTag("tab_sign_up"),
                                    text = {
                                        Text(
                                            text = "إنشاء حساب جديد",
                                            fontWeight = if (isSignUpMode) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.PersonAdd,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = if (isSignUpMode) {
                                    "أنشئ حساباً سحابياً موحداً بالبريد الإلكتروني وكلمة المرور لربط هاتف المنزل وهاتف التحكم تلقائياً"
                                } else {
                                    "سجّل الدخول بنفس الحساب على هاتف المنزل (Server) وهاتفك الشخصي (Admin) ليتم الاكتشاف والمزامنة اللحظية"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Email Input Field
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("البريد الإلكتروني (Email)") },
                                placeholder = { Text("example@domain.com") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = "أيقونة البريد الإلكتروني"
                                    )
                                },
                                singleLine = true,
                                enabled = !isLoading,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("email_input_field")
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Password Input Field
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("كلمة المرور (Password)") },
                                placeholder = { Text("6 أحرف أو أرقام على الأقل") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "أيقونة كلمة المرور"
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { isPasswordVisible = !isPasswordVisible },
                                        modifier = Modifier.testTag("toggle_password_visibility")
                                    ) {
                                        Icon(
                                            imageVector = if (isPasswordVisible) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = if (isPasswordVisible) {
                                                "إخفاء كلمة المرور"
                                            } else {
                                                "إظهار كلمة المرور"
                                            }
                                        )
                                    }
                                },
                                visualTransformation = if (isPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                singleLine = true,
                                enabled = !isLoading,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = if (isSignUpMode) ImeAction.Next else ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (!isSignUpMode) {
                                            focusManager.clearFocus()
                                            onEmailSignInClick(email, password)
                                        }
                                    }
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("password_input_field")
                            )

                            // Confirm Password Field (Only in Sign-Up Mode)
                            AnimatedVisibility(visible = isSignUpMode) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = confirmPassword,
                                        onValueChange = { confirmPassword = it },
                                        label = { Text("تأكيد كلمة المرور") },
                                        placeholder = { Text("أعد كتابة كلمة المرور للتأكيد") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Key,
                                                contentDescription = "أيقونة تأكيد كلمة المرور"
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    isConfirmPasswordVisible = !isConfirmPasswordVisible
                                                },
                                                modifier = Modifier.testTag("toggle_confirm_password_visibility")
                                            ) {
                                                Icon(
                                                    imageVector = if (isConfirmPasswordVisible) {
                                                        Icons.Default.VisibilityOff
                                                    } else {
                                                        Icons.Default.Visibility
                                                    },
                                                    contentDescription = if (isConfirmPasswordVisible) {
                                                        "إخفاء تأكيد كلمة المرور"
                                                    } else {
                                                        "إظهار تأكيد كلمة المرور"
                                                    }
                                                )
                                            }
                                        },
                                        visualTransformation = if (isConfirmPasswordVisible) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        singleLine = true,
                                        enabled = !isLoading,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                focusManager.clearFocus()
                                                onEmailSignUpClick(email, password, confirmPassword)
                                            }
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("confirm_password_input_field")
                                    )
                                }
                            }

                            // Forgot Password Button (Only in Sign-In Mode)
                            if (!isSignUpMode) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            onResetPasswordClick(email)
                                        },
                                        enabled = !isLoading,
                                        modifier = Modifier.testTag("forgot_password_button")
                                    ) {
                                        Text(
                                            text = "نسيت كلمة المرور؟ استعادة عبر البريد",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            // Primary Submit Button (Email Sign-In OR Create Account)
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (isSignUpMode) {
                                        onEmailSignUpClick(email, password, confirmPassword)
                                    } else {
                                        onEmailSignInClick(email, password)
                                    }
                                },
                                enabled = !isLoading,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("email_auth_submit_button")
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        if (isSignUpMode) "جاري إنشاء الحساب..." else "جاري تسجيل الدخول..."
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isSignUpMode) {
                                            Icons.Default.PersonAdd
                                        } else {
                                            Icons.Default.VpnKey
                                        },
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (isSignUpMode) {
                                            "إنشاء حساب جديد والدخول"
                                        } else {
                                            "تسجيل الدخول بالبريد وكلمة السر"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Switch Mode Helper Button
                            TextButton(
                                onClick = {
                                    isSignUpMode = !isSignUpMode
                                    onDismissError()
                                },
                                enabled = !isLoading,
                                modifier = Modifier.testTag("switch_auth_mode_button")
                            ) {
                                Text(
                                    text = if (isSignUpMode) {
                                        "لديك حساب بالفعل؟ اضغط هنا لتسجيل الدخول"
                                    } else {
                                        "ليس لديك حساب؟ اضغط هنا لإنشاء حساب جديد"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f))
                                Text(
                                    text = "  أو المتابعة السريعة عبر  ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f))
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Secondary Option: Google Sign-In Button
                            OutlinedButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    onGoogleSignInClick()
                                },
                                enabled = !isLoading,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("google_sign_in_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "الدخول السريع عبر حساب Google",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Architecture Feature Summary Cards
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FeaturePillCard(
                            icon = Icons.Default.Dns,
                            title = "خادم NanoHTTPD",
                            subtitle = "تدفق مباشر + 206 Range",
                            modifier = Modifier.weight(1f)
                        )
                        FeaturePillCard(
                            icon = Icons.Default.PlayCircleFilled,
                            title = "بث ExoPlayer",
                            subtitle = "تقديم وتأخير فوري",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturePillCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =============================================================================
// 2. MODE SELECTOR & PERMISSIONS SCREEN
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectorScreen(
    userEmail: String,
    permissionsState: SystemPermissionsState,
    discoveredHomeNode: HomeNodeInfo?,
    isLocalServerRunning: Boolean,
    onRequestStoragePermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSkipNotificationPermission: () -> Unit = {},
    onRequestBatteryOptimizationExemption: () -> Unit,
    onSelectMode: (AppMode) -> Unit,
    onSignOut: () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Smart Home Cloud",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = userEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onSignOut,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "تسجيل الخروج"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Permissions Readiness Card
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "الصلاحيات واستمرارية النظام",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (permissionsState.allGranted) {
                                    Color(0xFF10B981).copy(alpha = 0.18f)
                                } else {
                                    Color(0xFFF59E0B).copy(alpha = 0.18f)
                                }
                            ) {
                                Text(
                                    text = if (permissionsState.allGranted) "جاهزة للعمل" else "اختيارية / متاحة",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (permissionsState.allGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }

                        PermissionRowItem(
                            icon = Icons.Default.SdStorage,
                            title = "إدارة كل الملفات (MANAGE_EXTERNAL_STORAGE)",
                            subtitle = "مطلوبة لقراءة وكتابة ملفات الخادم المنزلي بالكامل",
                            isGranted = permissionsState.hasStorageAccess,
                            buttonText = "تفعيل",
                            testTag = "grant_storage_permission_button",
                            onClick = onRequestStoragePermission
                        )

                        PermissionRowItem(
                            icon = Icons.Default.NotificationsActive,
                            title = "الإشعارات الدائمة (اختيارية - POST_NOTIFICATIONS)",
                            subtitle = if (permissionsState.isNotificationSkipped && !permissionsState.hasNotificationPermission) {
                                "تم تفعيل الوضع الصامت: سيعمل الخادم في الخلفية بدون إظهار إشعار"
                            } else {
                                "اختيارية: لعرض حالة الخادم في شريط الإشعارات (يمكنك تخطيها والعمل بصمت)"
                            },
                            isGranted = permissionsState.hasNotificationPermission,
                            isOptionalSkipped = permissionsState.isNotificationSkipped,
                            buttonText = "سماح",
                            skipButtonText = "تخطي",
                            testTag = "grant_notification_permission_button",
                            onSkipClick = onSkipNotificationPermission,
                            onClick = onRequestNotificationPermission
                        )

                        PermissionRowItem(
                            icon = Icons.Default.BatteryChargingFull,
                            title = "استثناء تحسين البطارية (WakeLock / Doze)",
                            subtitle = "لمنع النظام من إيقاف الخادم المنزلي عند إغلاق الشاشة",
                            isGranted = permissionsState.isIgnoringBatteryOptimizations,
                            buttonText = "استثناء",
                            testTag = "grant_battery_exemption_button",
                            onClick = onRequestBatteryOptimizationExemption
                        )
                    }
                }

                Text(
                    text = "اختر نمط تشغيل هذا الهاتف",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Card 1: Home Node Server Mode
                ModeSelectionCard(
                    title = "Home Node Server Mode (Global WebRTC P2P)",
                    arabicTitle = "نمط الخادم المنزلي (الهاتف القديم)",
                    description = "تحويل هذا الهاتف إلى خادم سحابي منزلي يعمل عبر نفق WebRTC P2P وقناة RTCDataChannel مع مزامنة الإشارات عبر users/{uid}/signaling للوصول من أي شبكة في العالم.",
                    icon = Icons.Default.Dns,
                    badgeText = if (isLocalServerRunning) "الخادم يعمل الآن (WebRTC P2P)" else "جاهز للتشغيل",
                    badgePositive = isLocalServerRunning,
                    accentColor = MaterialTheme.colorScheme.primary,
                    testTag = "select_home_server_mode_card",
                    onClick = { onSelectMode(AppMode.HOME_NODE_SERVER) }
                )

                // Card 2: Admin Controller Mode
                val isRemoteOnline = discoveredHomeNode?.isOnline == true
                ModeSelectionCard(
                    title = "Admin Controller Mode (Global WebRTC P2P)",
                    arabicTitle = "نمط وحدة تحكم الآدمن (الهاتف الأساسي)",
                    description = "الاتصال بهاتف المنزل من أي مكان في العالم (4G/5G أو شبكات واي فاي مختلفة) عبر نفق WebRTC P2P لتصفح الملفات، سحبها، رفعها، ومراقبة الشاشة.",
                    icon = Icons.Default.AdminPanelSettings,
                    badgeText = if (isRemoteOnline) {
                        "متصل عالمياً عبر P2P: ${discoveredHomeNode?.deviceName}"
                    } else {
                        "بانتظار اتصال خادم المنزل"
                    },
                    badgePositive = isRemoteOnline,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    testTag = "select_admin_controller_mode_card",
                    onClick = { onSelectMode(AppMode.ADMIN_CONTROLLER) }
                )
            }
        }
    }
}

@Composable
private fun PermissionRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    isOptionalSkipped: Boolean = false,
    buttonText: String,
    skipButtonText: String? = null,
    testTag: String,
    onSkipClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = when {
                isGranted -> Color(0xFF10B981)
                isOptionalSkipped -> Color(0xFF38BDF8)
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        when {
            isGranted -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "ممنوحة",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(24.dp)
                )
            }
            isOptionalSkipped -> {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF38BDF8).copy(alpha = 0.16f),
                    modifier = Modifier.clickable { onClick() }
                ) {
                    Text(
                        text = "وضع صامت",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            else -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (skipButtonText != null && onSkipClick != null) {
                        TextButton(
                            onClick = onSkipClick,
                            modifier = Modifier.testTag("${testTag}_skip")
                        ) {
                            Text(skipButtonText)
                        }
                    }
                    FilledTonalButton(
                        onClick = onClick,
                        modifier = Modifier.testTag(testTag)
                    ) {
                        Text(buttonText)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSelectionCard(
    title: String,
    arabicTitle: String,
    description: String,
    icon: ImageVector,
    badgeText: String,
    badgePositive: Boolean,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = arabicTitle,
                            tint = accentColor,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (badgePositive) {
                        Color(0xFF10B981).copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (badgePositive) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = arabicTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// =============================================================================
// 3. HOME NODE SERVER SCREEN (Local HTTP Server + Live Console Logs)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeNodeServerScreen(
    isRunning: Boolean,
    nodeInfo: HomeNodeInfo?,
    logs: List<ServerLog>,
    statusMessage: String?,
    isMediaProjectionEnabled: Boolean = false,
    webRtcStateText: String = "",
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRequestMediaProjection: () -> Unit = {},
    onClearLogs: () -> Unit,
    onBackToModeSelector: () -> Unit
) {
    BackHandler(onBack = onBackToModeSelector)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "الخادم المنزلي (Home Node Server)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isRunning) "متصل ومزامن مع Firebase" else "متوقف حالياً",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBackToModeSelector,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("server_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "العودة لاختيار النمط"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("clear_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "مسح سجل الأحداث"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Server Status & Telemetry Card
                ElevatedCard(
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(if (isRunning) Color(0xFF10B981) else Color(0xFFEF4444))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isRunning) "ONLINE • الخادم نشط" else "OFFLINE • الخادم متوقف",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isRunning) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            }

                            Button(
                                onClick = if (isRunning) onStopServer else onStartServer,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRunning) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.testTag("toggle_server_button")
                            ) {
                                Icon(
                                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isRunning) "إيقاف الخادم" else "تشغيل الخادم",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TelemetryStatChip(
                                icon = Icons.Default.Router,
                                label = "مسار الإشارات العالمي (P2P)",
                                value = if (nodeInfo != null && isRunning) {
                                    "متاح عالمياً عبر P2P"
                                } else {
                                    "غير نشط"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryStatChip(
                                icon = Icons.Default.BatteryChargingFull,
                                label = "البطارية",
                                value = "${nodeInfo?.batteryLevel ?: 100}%",
                                modifier = Modifier.weight(0.7f)
                            )
                        }

                        if (nodeInfo != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "المساحة المتاحة: ${RemoteFileItem.formatBytes(nodeInfo.freeStorageBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "الإجمالي: ${RemoteFileItem.formatBytes(nodeInfo.totalStorageBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { nodeInfo.usedStorageFraction() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(50))
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = "رمز الجلسة",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "رمز الأمان (x-auth-token): ${nodeInfo.secretToken.take(16)}...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Screen Mirroring & WebRTC MediaProjection Quick Control
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "بث الشاشة المزدوج (/screenshot + WebRTC):",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isMediaProjectionEnabled) {
                                            "صلاحية MediaProjection مفعّلة • $webRtcStateText"
                                        } else {
                                            "PixelCopy نشط تلقائياً • $webRtcStateText"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledTonalButton(
                                    onClick = onRequestMediaProjection,
                                    modifier = Modifier.testTag("enable_media_projection_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ScreenShare,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isMediaProjectionEnabled) "مفعّل (30FPS)" else "بث الشاشة الكاملة")
                                }
                            }
                        }

                        statusMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Live Console Logs Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "سجل أحداث الخادم الفوري (Live Console Logs)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${logs.size} حدث",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Live Console Logs Terminal Box
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF070F1E),
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "لا توجد أحداث مسجلة بعد. اضغط على «تشغيل الخادم» لبدء الاستماع للطلبات.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("server_logs_list"),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(logs, key = { it.id }) { log ->
                                ConsoleLogItemRow(log = log)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryStatChip(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ConsoleLogItemRow(log: ServerLog) {
    val methodColor = when (log.method) {
        "GET" -> Color(0xFF38BDF8)
        "POST" -> Color(0xFF4ADE80)
        "DELETE" -> Color(0xFFF87171)
        "SYSTEM", "NETWORK" -> Color(0xFFA78BFA)
        else -> Color(0xFFFBBF24)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F172A))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "[${log.formattedTime()}]",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF64748B),
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = methodColor.copy(alpha = 0.18f)
        ) {
            Text(
                text = log.method,
                style = MaterialTheme.typography.labelSmall,
                color = methodColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (log.isError) Color(0xFFFCA5A5) else Color(0xFFE2E8F0),
            modifier = Modifier.weight(1f)
        )
    }
}

// =============================================================================
// 4. ADMIN CONTROLLER SCREEN (Realtime Discovery + Remote File Manager + Streaming)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminControllerScreen(
    discoveredNode: HomeNodeInfo?,
    isP2pConnected: Boolean = false,
    p2pConnectionStatus: String = "",
    remoteFiles: List<RemoteFileItem>,
    currentPath: String,
    rootPath: String,
    isLoadingFiles: Boolean,
    transferProgress: TransferProgress,
    activeStreamMedia: RemoteFileItem?,
    pendingDeleteItem: RemoteFileItem?,
    statusMessage: String?,
    errorMessage: String?,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    onBackToModeSelector: () -> Unit,
    onRefreshConnection: () -> Unit,
    onNavigateToDirectory: (String) -> Unit,
    onFileItemClick: (RemoteFileItem) -> Unit,
    onStreamFileClick: (RemoteFileItem) -> Unit,
    onDownloadFileClick: (RemoteFileItem) -> Unit,
    onDeleteFileClick: (RemoteFileItem) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onUploadFileSelected: (android.net.Uri) -> Unit,
    onCloseStreamPlayer: () -> Unit,
    buildStreamUrl: (RemoteFileItem) -> String?,
    onOpenScreenMirroring: () -> Unit = {},
    onDismissBanners: () -> Unit
) {
    // System BackHandler navigates up to parent folder first, then exits to Mode Selector
    BackHandler {
        if (canNavigateUp) {
            onNavigateUp()
        } else {
            onBackToModeSelector()
        }
    }

    val uploadFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onUploadFileSelected(uri)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "لوحة تحكم الآدمن (Global WebRTC P2P)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    isP2pConnected && discoveredNode?.isOnline == true ->
                                        "متصل عالمياً عبر P2P بـ ${discoveredNode.deviceName}"
                                    discoveredNode?.isOnline == true ->
                                        "متصل بـ ${discoveredNode.deviceName} • $p2pConnectionStatus"
                                    else ->
                                        "بانتظار اتصال هاتف المنزل..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (discoveredNode?.isOnline == true) {
                                    Color(0xFF10B981)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (canNavigateUp) onNavigateUp() else onBackToModeSelector()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("admin_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onOpenScreenMirroring,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("open_screen_mirroring_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenShare,
                                contentDescription = "بث شاشة الهاتف المزدوجة (Screen Mirroring)",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = onRefreshConnection,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("admin_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "تحديث الملفات وحالة الخادم"
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                if (discoveredNode?.isOnline == true) {
                    ExtendedFloatingActionButton(
                        onClick = { uploadFileLauncher.launch("*/*") },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "رفع ملف إلى هاتف المنزل"
                            )
                        },
                        text = { Text("رفع ملف للمنزل") },
                        modifier = Modifier.testTag("upload_fab_button")
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Automatic Realtime Home Node Discovery Card + Screen Mirroring Button
                HomeNodeDiscoveryHeaderCard(
                    node = discoveredNode,
                    onOpenScreenMirroring = onOpenScreenMirroring
                )

                // 2. Active Transfer Progress Indicator (Download / Upload)
                AnimatedVisibility(visible = transferProgress.isActive) {
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${if (transferProgress.isUpload) "رفع" else "تحميل"}: ${transferProgress.fileName}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${transferProgress.percentage}%",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { transferProgress.progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50))
                            )
                            Text(
                                text = transferProgress.statusMessage,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // 3. Status / Error Notification Banner
                if (!errorMessage.isNullOrBlank() || !statusMessage.isNullOrBlank()) {
                    val isErr = !errorMessage.isNullOrBlank()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isErr) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isErr) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (isErr) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = errorMessage ?: statusMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isErr) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissBanners) {
                                Text("حسناً")
                            }
                        }
                    }
                }

                // 4. Interactive Clickable Breadcrumb Bar
                if (discoveredNode?.isOnline == true) {
                    BreadcrumbBar(
                        currentPath = currentPath,
                        rootPath = rootPath,
                        onNavigateToPath = onNavigateToDirectory
                    )
                }

                // 5. Remote File Manager Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        discoveredNode == null || !discoveredNode.isOnline -> {
                            OfflineHomeNodePlaceholder()
                        }
                        isLoadingFiles -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "جاري جلب قائمة الملفات من هاتف المنزل...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        remoteFiles.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "هذا المجلد فارغ حالياً",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "استخدم زر «رفع ملف للمنزل» لإرسال ملفات إلى هذا المجلد",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("remote_files_list"),
                                contentPadding = PaddingValues(bottom = 88.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(remoteFiles, key = { it.path }) { item ->
                                    RemoteFileItemCard(
                                        item = item,
                                        onItemClick = { onFileItemClick(item) },
                                        onStreamClick = { onStreamFileClick(item) },
                                        onDownloadClick = { onDownloadFileClick(item) },
                                        onDeleteClick = { onDeleteFileClick(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Delete Confirmation AlertDialog
        if (pendingDeleteItem != null) {
            AlertDialog(
                onDismissRequest = onDismissDeleteDialog,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text(
                        text = "تأكيد حذف الملف عن بُعد",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "هل أنت متأكد من رغبتك في حذف «${pendingDeleteItem.name}» نهائياً من هاتف المنزل؟ لا يمكن التراجع عن هذه العملية."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.testTag("confirm_delete_button")
                    ) {
                        Text("حذف نهائي")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismissDeleteDialog) {
                        Text("إلغاء")
                    }
                }
            )
        }

        // Full-Screen Media3 ExoPlayer Stream Dialog
        if (activeStreamMedia != null && discoveredNode != null) {
            val streamUrl = buildStreamUrl(activeStreamMedia)
            if (!streamUrl.isNullOrBlank()) {
                MediaStreamPlayerDialog(
                    mediaItem = activeStreamMedia,
                    streamUrl = streamUrl,
                    secretToken = discoveredNode.secretToken,
                    onDismiss = onCloseStreamPlayer
                )
            }
        }
    }
}

@Composable
private fun HomeNodeDiscoveryHeaderCard(
    node: HomeNodeInfo?,
    onOpenScreenMirroring: () -> Unit = {}
) {
    val isOnline = node?.isOnline == true
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (isOnline) {
                            Color(0xFF10B981).copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (isOnline) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = node?.deviceName?.ifBlank { "هاتف المنزل الذكي" } ?: "لم يتم تسجيل خادم منزلي بعد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isOnline) {
                                "متصل عالمياً عبر P2P • ${node.effectiveSignalingPath()}"
                            } else {
                                "غير متصل حالياً (Offline)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (isOnline) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (node != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = "بطارية هاتف المنزل",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${node.batteryLevel}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (node != null && node.totalStorageBytes > 0L) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "المساحة المتبقية في هاتف المنزل: ${RemoteFileItem.formatBytes(node.freeStorageBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "من أصل ${RemoteFileItem.formatBytes(node.totalStorageBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { node.usedStorageFraction() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                    )
                }
            }

            FilledTonalButton(
                onClick = onOpenScreenMirroring,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_screen_mirroring_card_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ScreenShare,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "بث شاشة الهاتف المزدوجة (HTTP /screenshot + WebRTC Live)",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    currentPath: String,
    rootPath: String,
    onNavigateToPath: (String) -> Unit
) {
    val effectiveRoot = rootPath.ifBlank { "/" }
    val relative = if (currentPath.startsWith(effectiveRoot)) {
        currentPath.removePrefix(effectiveRoot).trim('/')
    } else {
        currentPath.trim('/')
    }
    val segments = if (relative.isEmpty()) emptyList() else relative.split("/")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToPath(effectiveRoot) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "المجلد الرئيسي",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "الرئيسية",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            itemsIndexed(segments) { index, segment ->
                val targetPath = buildString {
                    append(effectiveRoot.trimEnd('/'))
                    for (i in 0..index) {
                        append("/")
                        append(segments[i])
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = segment,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (index == segments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == segments.lastIndex) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigateToPath(targetPath) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteFileItemCard(
    item: RemoteFileItem,
    onItemClick: () -> Unit,
    onStreamClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val (icon, iconTint) = when {
        item.isDirectory -> Icons.Default.Folder to Color(0xFFF59E0B)
        item.isVideoFile() -> Icons.Default.VideoFile to Color(0xFF38BDF8)
        item.isAudioFile() -> Icons.Default.AudioFile to Color(0xFFA78BFA)
        item.isImageFile() -> Icons.Default.Image to Color(0xFF10B981)
        item.isArchiveFile() -> Icons.Default.Archive to Color(0xFFF97316)
        item.isDocumentFile() -> Icons.Default.Description to Color(0xFF60A5FA)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF94A3B8)
    }

    Card(
        onClick = onItemClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("file_item_${item.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = iconTint.copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = item.name,
                        tint = iconTint,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.formattedSize(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.isStreamableMedia()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "يدعم البث المباشر",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (!item.isDirectory) {
                if (item.isStreamableMedia()) {
                    IconButton(
                        onClick = onStreamClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("stream_button_${item.name}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "بث مباشر",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("download_button_${item.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "تحميل الملف",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_button_${item.name}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "حذف",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun OfflineHomeNodePlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "بانتظار اتصال الخادم المنزلي...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "افتح التطبيق على هاتف المنزل بنفس الحساب، واختر «نمط الخادم المنزلي» واضغط على تشغيل الخادم ليظهر هنا فوراً وبشكل تلقائي.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
