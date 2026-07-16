# Add project specific ProGuard rules here.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keep public class * extends android.webkit.WebChromeClient
