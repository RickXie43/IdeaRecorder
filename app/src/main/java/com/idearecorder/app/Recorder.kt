package com.idearecorder.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

class Recorder(private val context: Context) {
    private var audio: AudioRecord? = null
    private var thread: Thread? = null
    private var current: File? = null
    private var running = false
    private var bytesWritten = 0
    fun start(): File { val dir=File(context.filesDir,"recordings").apply{mkdirs()}; val file=File(dir,"note_${System.currentTimeMillis()}.wav"); val rate=16000; val buffer=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(rate); val rec=AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer); audio=rec;current=file;bytesWritten=0;running=true;thread=Thread{RandomAccessFile(file,"rw").use{out->out.setLength(44);val data=ByteArray(buffer);rec.startRecording();while(running){val n=rec.read(data,0,data.size);if(n>0){out.seek(out.length());out.write(data,0,n);bytesWritten+=n}};writeHeader(out,rate,bytesWritten)};rec.stop();rec.release()}.also{it.start()};return file }
    private fun writeHeader(out:RandomAccessFile,rate:Int,dataLength:Int){out.seek(0);out.writeBytes("RIFF");out.writeInt(Integer.reverseBytes(36+dataLength));out.writeBytes("WAVEfmt ");out.writeInt(Integer.reverseBytes(16));out.writeShort(java.lang.Short.reverseBytes(1).toInt());out.writeShort(java.lang.Short.reverseBytes(1).toInt());out.writeInt(Integer.reverseBytes(rate));out.writeInt(Integer.reverseBytes(rate*2));out.writeShort(java.lang.Short.reverseBytes(2).toInt());out.writeShort(java.lang.Short.reverseBytes(16).toInt());out.writeBytes("data");out.writeInt(Integer.reverseBytes(dataLength))}
    fun stop(): File? {running=false;thread?.join(1500);thread=null;return current}
    fun cancel(){running=false;thread?.join(1500);thread=null;current?.delete();current=null}
}
