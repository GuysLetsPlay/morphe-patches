-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.morphe.** {
  *;
}
-keep class app.hxreborn.extension.aliexpress.** {
  *;
}
-keep class app.hxreborn.extension.shared.** {
  *;
}
-keep class com.google.** {
  *;
}
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier
