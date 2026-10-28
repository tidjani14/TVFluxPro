================================================================================
🚀 LIVE PRO v20.0 — النسخة الإنتاجية النهائية الشاملة لجميع الشاشات والهواتف والسيرفرات
================================================================================

أهم الإصلاحات الجذرية في الإصدار النهائي v20.0:
1. حل نهائي لمشكلة دوران عجلة التحميل (⏳ جارٍ التحميل...) في شاشات التلفاز (Android TV / TV Box / Fire TV):
   - إزالة قفل الذاكرة (setPrioritizeTimeOverSizeThresholds(false) + LITE_LOW_RAM 8MB) الذي كان يفعّل تلقائياً في شاشات التلفاز فقط بسبب حساس الرام (DevicePerformanceDetector)، مما كان يؤدي إلى توقف ExoPlayer في حالة STATE_BUFFERING للأبد قبل بدء عرض الفيديو.
   - توحيد إعدادات DefaultLoadControl بين الهاتف والتلفاز (setPrioritizeTimeOverSizeThresholds(true) دائماً) ليفتح البث المباشر والأفلام والمسلسلات في التلفاز بنفس السرعة الفورية للهاتف.
   - إلغاء نفق tun0 المحلي القديم في CloudflareWarpVpnService الذي كان يسبب حظر IP الداتا سنتر أو تقطيع حزم الفيديو (MTU Blackhole) على بعض شاشات التلفاز، واستبداله بدرع الاتصال المباشر الآمن (Direct Cloudflare DoH Shield) مع أولوية IPv4 الفورية (Inet4Address First).
   - إزالة علم FLAG_DETECT_ACCESS_UNITS وتقليل حجم فحص التوقيت في TsExtractor إلى (188 * 3200) لتشغيل قنوات MPEG-TS و HLS فوراً على معالجات الشاشات.
   - إضافة مؤقت حماية ذكي (Buffering Watchdog Timer 6.5s) يقوم بالتبديل التلقائي الفوري بين صيغ (.m3u8 <-> .ts <-> الرابط الخام) إذا تأخر السيرفر في إرسال الفيديو دون تجميد عجلة التحميل.
   - تحرير الاتصالات الخاملة تلقائياً (evictIdleApiConnections) فور بدء المشاهدة لضمان عدم تعارض مشغل الفيديو مع حسابات السيرفرات ذات الاتصال الواحد (max_connections = 1).

2. التعرف التلقائي الذكي على جميع السيرفرات (KnownIptvServersCatalog + server_portal/data.txt):
   - دمج قاعدة بيانات لـ 43+ سيرفر IPTV / Xtream عالمي (Temu VIP / tmasta.com، King 365، Nexon، IRON PRO MAX، TREX 4K، DINO، STRONG 8K، MEGA OTT، CRYSTAL، MAGNUM، GOLD TV، ATLAS PRO، LYNX، SANSAT، STAR SHARE، LIONZ، COBRA، FALCON، SPIDER، B1G، EAGLE، ROYAL، DIAMOND، NOVA، INFINITY، MATADOR، HAWK وغيرها) للتعرف التلقائي على السيرفر وتبديل الهوست البديل تلقائياً عند الحاجة.
   - توجيه تركيز ريموت التلفاز (D-Pad Focus) تلقائياً إلى الحلقة الأولى عند فتح أي مسلسل.
================================================================================
