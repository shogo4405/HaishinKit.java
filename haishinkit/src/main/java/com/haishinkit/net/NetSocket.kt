package com.haishinkit.net

import android.util.Log
import androidx.core.util.Pools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.lang3.builder.ToStringBuilder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.CoroutineContext

internal abstract class NetSocket : CoroutineScope {
    var timeout = DEFAULT_TIMEOUT

    val totalBytesIn = AtomicLong(0)
    val totalBytesOut = AtomicLong(0)
    val queueBytesOut = AtomicLong(0)
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO
    private var inputBuffer = ByteBuffer.allocate(DEFAULT_WINDOW_SIZE_C)
    private var socket: java.net.Socket? = null
    private var inputStream: InputStream? = null
        set(value) {
            if (value == null) {
                field?.close()
            }
            field = value
        }
    private var outputStream: OutputStream? = null
        set(value) {
            if (value == null) {
                field?.close()
            }
            field = value
        }
    private var outputQueue = LinkedBlockingDeque<ByteBuffer>()
    private var outputBufferPool = Pools.SimplePool<ByteBuffer>(1024)
    @Volatile private var keepAlive = false

    fun connect(dstName: String, dstPort: Int, isSecure: Boolean) {
        keepAlive = true
        totalBytesIn.set(0)
        totalBytesOut.set(0)
        queueBytesOut.set(0)
        launch(coroutineContext) {
            doConnection(dstName, dstPort, isSecure)
        }
    }

    open fun close(disconnected: Boolean) {
        keepAlive = false
        inputStream = null
        outputStream = null
        socket?.close()
        outputQueue.clear()
        inputBuffer.clear()
    }

    fun doOutput(buffer: ByteBuffer) {
        try {
            buffer.flip()
            queueBytesOut.addAndGet(buffer.remaining().toLong())
            outputQueue.put(buffer)
        } catch (e: InterruptedException) {
            Log.v(TAG, "", e)
        }
    }

    fun createByteBuffer(capacity: Int): ByteBuffer {
        synchronized(outputBufferPool) {
            var byteBuffer = outputBufferPool.acquire() ?: ByteBuffer.allocate(capacity)
            if (byteBuffer.capacity() != capacity) {
                byteBuffer = ByteBuffer.allocate(capacity)
            } else {
                byteBuffer.clear()
            }
            return byteBuffer
        }
    }

    override fun toString(): String {
        return ToStringBuilder.reflectionToString(this)
    }

    protected abstract fun onTimeout()
    protected abstract fun onConnect()
    protected abstract fun listen(buffer: ByteBuffer)

    private fun doInput() {
        try {
            val inputStream = inputStream ?: return
            val offset = inputBuffer.position()
            val result = inputStream.read(inputBuffer.array(), offset, inputBuffer.remaining())
            if (-1 < result) {
                inputBuffer.position(offset + result)
                inputBuffer.flip()
                totalBytesIn.addAndGet(result.toLong())
                listen(inputBuffer)
                if (inputBuffer.hasRemaining()) {
                    val remaining = inputBuffer.slice()
                    inputBuffer.clear()
                    inputBuffer.put(remaining)
                } else {
                    inputBuffer.clear()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "", e)
            close(true)
        }
    }

    private fun doOutput() {
        while (keepAlive) {
            val buffer = outputQueue.take()
            try {
                val outputStream = outputStream ?: break
                val remaining = buffer.remaining().toLong()
                outputStream.write(buffer.array(), 0, buffer.remaining())
                outputStream.flush()
                outputQueue.remove(buffer)
                totalBytesOut.addAndGet(remaining)
                queueBytesOut.addAndGet(remaining * -1)
                synchronized(outputBufferPool) {
                    outputBufferPool.release(buffer)
                }
            } catch (e: IOException) {
                Log.w(TAG, "", e)
                close(false)
            }
        }
    }

    private fun doConnection(dstName: String, dstPort: Int, isSecure: Boolean) {
        try {
            outputQueue.clear()
            val socket = createSocket(dstName, dstPort, isSecure)
            this.socket = socket
            if (socket.isConnected) {
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
                launch(Dispatchers.IO) {
                    doOutput()
                }
                onConnect()
                while (keepAlive) {
                    doInput()
                    try {
                        Thread.sleep(KEEP_ALIVE_SLEEP_INTERVAL)
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "", e)
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "", e)
            close(false)
            onTimeout()
        } catch (e: Exception) {
            Log.w(TAG, "", e)
            close(true)
        }
    }

    private fun createSocket(dstName: String, dstPort: Int, isSecure: Boolean): java.net.Socket {
        if (isSecure) {
            val socket = SSLSocketFactory.getDefault().createSocket(dstName, dstPort) as SSLSocket
            socket.startHandshake()
            return socket
        }
        return java.net.Socket(dstName, dstPort)
    }

    companion object {
        const val DEFAULT_TIMEOUT: Int = 1000

        private const val DEFAULT_WINDOW_SIZE_C = Short.MAX_VALUE.toInt()
        private const val KEEP_ALIVE_SLEEP_INTERVAL = 100L
        private val TAG = NetSocket::class.java.simpleName
    }
}
