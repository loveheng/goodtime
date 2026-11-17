package app.shiguang.shiguang

/**
 * P5 切流后 debug 变体主入口（D14 裁决 debug-only 口径延续）：仅覆写调试桥
 * 挂载钩子——语义树/自定义数据动作 + PixelCopy 截图（port/token 进 logcat），
 * release 无本类零编译进包。其余行为（AppGate 闸门/分享直投/首 Activity
 * 装配段）全在生产版 [NativeMainActivity]。
 */
class DebugNativeMainActivity : NativeMainActivity() {
    override fun attachDebugBridge() {
        NativeDebugBridgeWiring.attach(this)
    }
}
