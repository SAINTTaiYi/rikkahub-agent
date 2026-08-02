package me.rerere.rikkahub.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import com.dokar.sonner.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.File

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCropError: (Throwable) -> Unit = {},
    onCleanup: (() -> Unit)? = null,
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultIntent = result.data
        val returnedOutput = resultIntent?.let(UCrop::getOutput)
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val error = resultIntent?.let(UCrop::getError)
            when {
                error != null -> onCropError(error)
                returnedOutput == null -> onCropError(IllegalStateException("Image crop returned no output"))
                !runCatching { returnedOutput.toFile().isFile }.getOrDefault(false) ->
                    onCropError(IllegalStateException("Image crop output is missing"))
                else -> onCroppedImageReady(returnedOutput)
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            onCropError(
                resultIntent?.let(UCrop::getError)
                    ?: IllegalStateException("Image crop failed without an error detail"),
            )
        }
        cropOutputUri?.toFile()?.delete()
        if (returnedOutput != cropOutputUri) returnedOutput?.let { runCatching { it.toFile().delete() } }
        cropOutputUri = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.png")
        cropOutputUri = Uri.fromFile(outputFile)

        var crop = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(freeStyleCropEnabled)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            setCompressionFormat(Bitmap.CompressFormat.PNG)
        }).withMaxResultSize(4096, 4096)
        aspectRatio?.let { (x, y) ->
            crop = crop.withAspectRatio(x, y)
        }
        val cropIntent = crop.getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}
