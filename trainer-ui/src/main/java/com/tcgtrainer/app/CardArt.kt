package com.tcgtrainer.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tcgtrainer.engine.CardDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

private object ArtCache { val bitmaps = android.util.LruCache<String, ImageBitmap>(32) }
@Composable internal fun CardArt(card: CardDefinition, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(card.id) { mutableStateOf(ArtCache.bitmaps.get(card.id)) }
    LaunchedEffect(card.id) {
        bitmap = withContext(Dispatchers.IO) {
            card.artAsset?.let { path -> runCatching {
                if (!path.startsWith("remote:")) context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                else {
                    val uri = URI(path.removePrefix("remote:"))
                    require(uri.scheme == "https" && uri.host == "assets.tcgdex.net")
                    val directory = File(context.cacheDir, "catalog-art").apply { mkdirs() }
                    val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
                    val cached = File(directory, "$key.webp")
                    if (!cached.exists()) {
                        val connection = uri.toURL().openConnection() as HttpURLConnection
                        val bytes = try {
                            connection.connectTimeout = 6000; connection.readTimeout = 6000
                            require(connection.responseCode == 200)
                            connection.inputStream.use { stream ->
                                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                while (true) { val n = stream.read(buffer); if (n < 0) break; output.write(buffer, 0, n); require(output.size() <= 2_000_000) }
                                output.toByteArray()
                            }
                        } finally { connection.disconnect() }
                        val temporary = File.createTempFile("art-", ".tmp", directory)
                        temporary.writeBytes(bytes); temporary.renameTo(cached); temporary.delete()
                        val files = directory.listFiles().orEmpty().sortedBy { it.lastModified() }
                        var size = files.sumOf { it.length() }
                        for (old in files) { if (size <= 64_000_000) break; size -= old.length(); old.delete() }
                    }
                    BitmapFactory.decodeFile(cached.path)?.asImageBitmap()
                }
            }.getOrNull() }
                ?.also { ArtCache.bitmaps.put(card.id, it) }
        }
    }
    if (bitmap != null) Image(bitmap!!, contentDescription = "${card.name}, ${card.set} ${card.number}", modifier = modifier.clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Fit)
    else Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF775A32)).border(1.dp, Color(0xFFE2BD74), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        Text(card.name, modifier = Modifier.padding(4.dp), fontSize = 10.sp)
    }
}
@Composable internal fun CardBack(modifier: Modifier = Modifier, label: String = "TCG") {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF152D51)).border(1.dp, Color(0xFF567AA5), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(21.dp).border(1.dp, Color(0xFF6E96C5), RoundedCornerShape(50)))
        Text(label, fontSize = 8.sp, color = Color(0xFFBBD6F5), fontWeight = FontWeight.Bold)
    }
}
