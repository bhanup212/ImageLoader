package com.imageloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.os.AsyncTask
import android.os.Environment
import android.os.Environment.isExternalStorageRemovable
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.jakewharton.disklrucache.DiskLruCache
import java.io.*
import java.net.HttpURLConnection
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val DISK_CACHE_SIZE:Long = 1024 * 1024 * 10 // 10MB
private const val DISK_CACHE_SUBDIR = "thumbnails"

class ImageLoader private constructor() {

    val TAG = ImageLoader::class.java.simpleName

    companion object{
        private var mCache:ImageLruCache?=null
        private var diskLruCache: DiskLruCache? = null
        private var INSTANCE: ImageLoader? = null
        private var context:Context?=null

        fun get(ctx: Context): ImageLoader {
            context = ctx
            if (INSTANCE == null) {
                INSTANCE = ImageLoader()
            }

            if (mCache == null){
                val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
                val limitKb = maxKb/8
                mCache = ImageLruCache(limitKb)
            }

            return INSTANCE!!
        }

        private fun getDiskCacheDir(context: Context, uniqueName: String): File {

            val cachePath =
                if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()
                    || !isExternalStorageRemovable()) {
                    context.externalCacheDir?.path
                } else {
                    context.cacheDir.path
                }

            return File(cachePath + File.separator + uniqueName)
        }
    }

    init {
        if (diskLruCache == null){
            val cacheDir = getDiskCacheDir(context!!, DISK_CACHE_SUBDIR)
            InitDiskCacheTask().execute(cacheDir)
        }
    }


    private val diskCacheLock = ReentrantLock()
    private val diskCacheLockCondition: Condition = diskCacheLock.newCondition()
    private var diskCacheStarting = true


    private var imageSrcUrl: String? = null
    private var imageView: ImageView? = null
    private var defaultImg: Int? = null



    fun load(src: String): ImageLoader {
        imageSrcUrl = src
        return this
    }

    fun default(resourceId: Int): ImageLoader {
        defaultImg = resourceId
        return this
    }

    fun into(destinationImg: ImageView) {
        imageView = destinationImg

        val mCacheBitmap = getBitmapFromLru()
        val diskBitmap = getBitmapFromDisk(imageSrcUrl!!)

        when {
            mCacheBitmap != null -> {
                Log.d(TAG,"From memory mCache")
                imageView?.setImageBitmap(mCacheBitmap)
            }
            diskBitmap != null -> {
                Log.d(TAG,"From disk mCache")
                imageView?.setImageBitmap(diskBitmap)
            }
            else -> getBitmapFromInternet()
        }
    }

    private fun getBitmapFromInternet() {
        if (imageSrcUrl == null) {
            throw Throwable("Image url is required")
        }
        ImageDownloader(imageView!!,defaultImg).execute(imageSrcUrl)
    }

    private fun getBitmapFromLru():Bitmap?{
        return mCache!!.get(imageSrcUrl)
    }

    private fun getBitmapFromDisk(url: String): Bitmap? {

        val key = formatKey(url)
        val snapshot: DiskLruCache.Snapshot? = diskLruCache?.get(key)
        return if (snapshot != null) {
            val inputStream: InputStream = snapshot.getInputStream(0)
            val buffIn = BufferedInputStream(inputStream, 8 * 1024)
            BitmapFactory.decodeStream(buffIn)
        } else {
            null
        }

    }

    private fun putDiskLruCache(url: String, bitmap: Bitmap) {
        val key: String = formatKey(url)
        var editor: DiskLruCache.Editor? = null
        try {
            editor = diskLruCache?.edit(key)
            if (editor == null) {
                return
            }
            if (writeBitmapToFile(bitmap, editor)) {
                diskLruCache?.flush()
                editor.commit()
            } else {
                editor.abort()
            }
        } catch (e: IOException) {
            try {
                editor?.abort()
            } catch (ignored: IOException) {
            }
        }
    }

    private fun writeBitmapToFile(bitmap: Bitmap, editor: DiskLruCache.Editor): Boolean {
        var out: OutputStream? = null
        try {
            out = BufferedOutputStream(editor.newOutputStream(0), 8 * 1024)
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            out?.close()
        }
    }
    private fun formatKey(url:String):String{
        val MAX_LENGTH = 64
        var url = if (url.length>MAX_LENGTH) url.substring(0,MAX_LENGTH) else url
        return url.toLowerCase().replace("[^a-z0-9_-]".toRegex(),"_")
    }

    inner class ImageDownloader(val imageView: ImageView,val imageDefault:Int?) : AsyncTask<String, Void, Bitmap>() {

        override fun onPreExecute() {
            if (imageDefault != null) {
                imageView.setImageResource(imageDefault)
            }
        }

        override fun onPostExecute(result: Bitmap?) {
            if (result == null){
                return
            }
            imageView.setImageBitmap(result)
        }

        override fun doInBackground(vararg p0: String?): Bitmap? {
            try {
                val url = java.net.URL(p0[0])
                val connection = url
                    .openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()
                val input = connection.inputStream
                val bmp =  BitmapFactory.decodeStream(input)

                mCache?.put(p0[0]!!,bmp)
                putDiskLruCache(p0[0]!!,bmp)
                return bmp
            }catch (e: IOException){
                return null
            }

        }

    }

    internal class ImageLruCache(maxSize:Int): LruCache<String, Bitmap>(maxSize) {

        override fun sizeOf(key: String?, value: Bitmap?): Int {
            val kbOfBitmap = value?.byteCount!!/1024
            return kbOfBitmap
        }

    }

     inner class InitDiskCacheTask : AsyncTask<File, Void, Void>() {
        override fun doInBackground(vararg params: File): Void? {
            diskCacheLock.withLock {
                val cacheDir = params[0]
                diskLruCache = DiskLruCache.open(cacheDir, 1,1,DISK_CACHE_SIZE)
                diskCacheStarting = false // Finished initialization
                diskCacheLockCondition.signalAll() // Wake any waiting threads
            }
            return null
        }
    }


}