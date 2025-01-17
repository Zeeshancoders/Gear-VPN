package com.kpstv.vpn.extensions.utils

import com.kpstv.vpn.di.app.AppScope
import com.kpstv.vpn.logging.Logger
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// https://github.com/KaustubhPatange/Moviesy/blob/master/app/src/main/java/com/kpstv/yts/extensions/utils/RetrofitUtils.kt
@AppScope
class NetworkUtils @Inject constructor() {

  companion object {
    /**
     * Returns the body of the response & closes the response
     */
    fun Response.getBodyAndClose(): String? {
      try {
        val data = body?.string()
        body?.close()
        return data
      } catch (e: SocketException) {
        Logger.w(e, "Socket exception: ${e.message}")
      } catch (e: ProtocolException) {
        Logger.w(e, "Protocol exception: ${e.message}")
      }
      return null
    }
  }

  fun getRetrofitBuilder(): Retrofit.Builder {
    return Retrofit.Builder().apply {
      addConverterFactory(MoshiConverterFactory.create())
      client(getHttpClient())
    }
  }

  fun getHttpBuilder(): OkHttpClient.Builder {
    return OkHttpClient.Builder()
      .connectTimeout(0, TimeUnit.MINUTES)
      .readTimeout(0, TimeUnit.MINUTES)
      .callTimeout(0, TimeUnit.MINUTES)
  }

  /**
   * @param addLoggingInterceptor If true logcat will display all the Http request messages
   */
  fun getHttpClient(addLoggingInterceptor: Boolean = false): OkHttpClient {
    val client = getHttpBuilder()
    if (addLoggingInterceptor) {
      val loggingInterceptor = HttpLoggingInterceptor()
      loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
      client.addInterceptor(loggingInterceptor)
    }
    client.ignoreAllSSLErrors()
    return client.build()
  }

  suspend fun simpleGetRequest(url: String): Response =
    getHttpClient().newCall(Request.Builder().url(url).build()).await()

  private suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
      enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (continuation.isCancelled) return
          continuation.resumeWithException(e) // re-throw exception at the last suspension point
        }

        override fun onResponse(call: Call, response: Response) {
          continuation.resume(response)
        }
      })
      continuation.invokeOnCancellation {
        try {
          cancel()
        } catch (ex: Throwable) {
          ex.printStackTrace()
        }
      }
    }
  }

  private fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
    val naiveTrustManager = object : X509TrustManager {
      override fun getAcceptedIssuers(): Array<out X509Certificate> = arrayOf()
      override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
      override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
    }

    val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
      val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
      init(null, trustAllCerts, SecureRandom())
    }.socketFactory

    val verifiedHosts = HostnameVerifier host@{ hostname, _ ->
      return@host hostname.contains(
        "vpngate|vpnbook|github|ip-api".toRegex()
      )
    }

    sslSocketFactory(insecureSocketFactory, naiveTrustManager)
    hostnameVerifier(verifiedHosts)

    HttpsURLConnection.setDefaultSSLSocketFactory(insecureSocketFactory)
    HttpsURLConnection.setDefaultHostnameVerifier(verifiedHosts)

    return this
  }
}