# R8 / 资源压缩的 keep 规则（当前 release 没开 minify，这里是留给以后开的）。
#
# 依据（2026-10 审计）：
#   - 全仓库没有反射：Class.forName / getDeclaredMethod 均 0 命中
#   - 没有动态找资源：getIdentifier 0 命中（主题 overlay 全走 R.style.*）
#   - 没有自定义 View 从 XML 反射构造（<com.aris 0 命中），没有 @Keep
#   - 没有 native 代码（无 CMakeLists / jniLibs / .so）
# 所以开 minify 时先只依赖 Room / Glide 自带的 consumer 规则；出问题再往这里加 keep。
#
# v0.2.203：release 真的开了 R8（isMinifyEnabled = true）。
#
# 只裁不改名（-dontobfuscate）：体积收益几乎全在「删掉用不到的代码」这一步，而改名带来的
# 风险是实打实的 —— Room 用类名去找 <数据库类名>_Impl，Glide 也按名字找生成的
# AppGlideModule，漏一条 keep 规则就是装上就崩。留着名字还有两个好处：用户的崩溃堆栈仍然
# 可读；能直接在 build/outputs/mapping/release/usage.txt 里核对哪些类被删掉了。
-dontobfuscate

#
# v0.2.212：无障碍服务的名字换成了系统读屏「选择朗读」的包名 + 类名（见 AndroidManifest 里那段注释）。
# 这个类只在清单里出现、代码里没人 new 它 —— 显式 keep，免得 R8 判定它没用、连名字一起删掉。
-keep class com.google.android.accessibility.selecttospeak.SelectToSpeakService { *; }
