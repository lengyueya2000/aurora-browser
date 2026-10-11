package dev.aurora.browser

import android.app.Application
import android.content.Context

/**
 * 上次崩溃的堆栈记录。
 *
 * 为什么要有这个：真机上的闪退经常复现不了——同一份 APK 在模拟器（Android 14）上跑得好好的，
 * 到了 Android 15/16 的机型上点一下就没。没有堆栈就只能靠猜，猜错一轮就是一次发版。
 * 这里把未捕获异常落到 `SharedPreferences`（崩溃瞬间写文件更危险，写 prefs 是原子的），
 * 下次启动时由 `MainActivity` 弹出来，用户可以直接复制粘贴给开发者。
 */
object CrashLog {

    private const val PREFS = "aurora_crash"
    private const val KEY_LAST = "last_crash"

    /** 只留这么长：够看清异常类型、消息和前十来帧，又不至于把 prefs 撑爆。 */
    private const val MAX_CHARS = 6000

    /** 装到进程上。要在 `Application.onCreate` 里尽早调用。 */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, error) }
            // 记完还是要交给原来的处理器：Android 靠它弹「应用已停止运行」并回收进程，
            // 自己吞掉异常会让进程变成一个没有界面的僵尸。
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun record(context: Context, error: Throwable) {
        val text = buildString {
            append(error.javaClass.name)
            append(": ")
            append(error.message.orEmpty())
            append('\n')
            append(error.stackTraceToString())
        }.take(MAX_CHARS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST, text)
            .commit() // 进程马上要死了，apply 的异步写不保证落盘
    }

    /** 上次崩溃的堆栈；没有则为 null。 */
    fun peek(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    /** 用户看过并关掉之后清掉，免得每次启动都弹。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).commit()
    }
}
