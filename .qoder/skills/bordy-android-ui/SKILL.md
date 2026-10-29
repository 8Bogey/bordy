---
name: bordy-android-ui
description: قواعد تعديل واجهة Compose العربية RTL في بوردي — المصطلحات، نظام التصميم، الأيقونات، والنصوص، وكيفية التحقق بصرياً.
---

# واجهة بوردي — Compose عربي RTL

## قبل التعديل

- اقرأ الشاشة المستهدفة في `app/src/main/java/com/bordy/app/screens/`، والمكونات في `ui/components/DesignSystem.kt`، والسمات في `ui/theme/` و`utils/AppColors.kt`.
- الحالة المشتركة في `viewmodel/MainViewModel.kt`؛ التنقّل في `RootNavigation.kt`.
- النصوص الثابتة في `res/values/strings.xml`.

## قواعد ملزمة

- عربي RTL دائماً. لا تعكس التخطيط يدوياً؛ استخدم `LocalLayoutDirection`/`Modifier.rtl` حسب الموجود في المشروع.
- المصطلحات: «حجم/أحجام» للـ variant، «قطعة/القطع» لسطر البورد/عنصر القالب. ممنوع «متغير»، «سعة»، «بند».
- سعر المخزون مرجع التسعير؛ لا تحسب الأسعار داخل الواجهة — استخدم `utils/ProjectItemMath.kt` و`TemplateMath.kt`.
- الأيقونات: أيقونات Material حقيقية من `material-icons-extended` أو `utils/AppIcons.kt`؛ لا أيقونات شبكية/مربعات، وجرّب الشكل الفعلي قبل الاعتماد.
- الأرقام والعملة عبر `utils/FormatUtils.kt`؛ لا تنسيق يدوي متفرق.
- الحوارات والأوراق السفلية: أبقِ أزرار الإجراء ظاهرة مع الكيبورد مفتوحاً، واختبر ذلك على الجهاز.

## التحقق

- بعد التعديل: `build-debug` → `install-device` → `verify-ui` ثم لقطة شاشة للحكم على الشكل فقط.
- في Compose: النقر يضع المؤشر عند 0 في حقول النص، والكيبورد يحجب الأزرار — راجع skill `bordy-device-verify`.
