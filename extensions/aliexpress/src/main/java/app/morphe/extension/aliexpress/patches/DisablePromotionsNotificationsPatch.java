package app.morphe.extension.aliexpress.patches;

@SuppressWarnings("unused")
public final class DisablePromotionsNotificationsPatch {

    public static boolean shouldShowNotification() {
        return false;
    }

    public static boolean isPromotionalNotification(String title, String body) {
        if (title == null) title = "";
        if (body == null) body = "";

        return containsIgnoreCase(title, "promotion")
            || containsIgnoreCase(title, "coupon")
            || containsIgnoreCase(title, "deal")
            || containsIgnoreCase(title, "sale")
            || containsIgnoreCase(body, "promotion")
            || containsIgnoreCase(body, "coupon")
            || containsIgnoreCase(body, "discount");
    }

    private static boolean containsIgnoreCase(String str, String searchStr) {
        if (str == null || searchStr == null) return false;
        final int length = searchStr.length();
        if (length == 0) return true;

        final char firstLower = Character.toLowerCase(searchStr.charAt(0));
        final char firstUpper = Character.toUpperCase(searchStr.charAt(0));
        final int limit = str.length() - length;

        for (int i = 0; i <= limit; i++) {
            char c = str.charAt(i);
            if (c == firstLower || c == firstUpper) {
                if (str.regionMatches(true, i, searchStr, 0, length)) {
                    return true;
                }
            }
        }
        return false;
    }
}
