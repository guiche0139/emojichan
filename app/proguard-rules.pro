# R8 / 资源压缩的 keep 规则（当前 release 没开 minify，这里是留给以后开的）。
#
# 依据（2026-10 审计）：
#   - 全仓库没有反射：Class.forName / getDeclaredMethod 均 0 命中
#   - 没有动态找资源：getIdentifier 0 命中（主题 overlay 全走 R.style.*）
#   - 没有自定义 View 从 XML 反射构造（<com.aris 0 命中），没有 @Keep
#   - 没有 native 代码（无 CMakeLists / jniLibs / .so）
# 所以开 minify 时先只依赖 Room / Glide 自带的 consumer 规则；出问题再往这里加 keep。
