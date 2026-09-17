package com.idearecorder.app

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class OfflineTranscriber(private val context: Context) {
    private var model: Model? = null
    fun status(): String = if (File(context.filesDir, "vosk-model-small-cn-0.22").exists()) "ready" else "missing"
    fun prepareFromAssets(): Boolean {
        val target=File(context.filesDir,"vosk-model-small-cn-0.22"); if(target.exists()) return true
        return runCatching { context.assets.open("vosk-model-small-cn-0.22.zip").use { input -> ZipInputStream(input).use { zip -> var e=zip.nextEntry; while(e!=null){ val out=File(context.filesDir,e.name); if(e.isDirectory) out.mkdirs() else {out.parentFile?.mkdirs();FileOutputStream(out).use{zip.copyTo(it)}}; e=zip.nextEntry } } }; true }.getOrDefault(false)
    }
    fun transcribe(wavPath: String): String {
        if (!prepareFromAssets()) error("离线中文模型未安装")
        if(model==null) model=Model(File(context.filesDir,"vosk-model-small-cn-0.22").absolutePath)
        val rec=Recognizer(model!!,16000f); val all=File(wavPath).readBytes(); val bytes=if(all.size>44) all.copyOfRange(44,all.size) else all; rec.acceptWaveForm(bytes,bytes.size); val result=JSONObject(rec.getFinalResult()).optString("text"); rec.close(); return result
    }
}
