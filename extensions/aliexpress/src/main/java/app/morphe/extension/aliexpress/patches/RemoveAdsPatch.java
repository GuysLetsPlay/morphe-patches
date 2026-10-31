package app.morphe.extension.aliexpress.patches;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public final class RemoveAdsPatch {

    public static boolean shouldShowAd() {
        return false;
    }

    public static List<Object> filterAds(List<Object> items) {
        if (items == null || items.isEmpty()) return items;
        List<Object> filtered = new ArrayList<>();
        for (Object item : items) {
            if (!isAdItem(item)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private static boolean isAdItem(Object item) {
        if (item == null) return false;
        String cls = item.getClass().getName();
        return containsIgnoreCase(cls, "aditem")
            || containsIgnoreCase(cls, "sponsored")
            || containsIgnoreCase(cls, "advertisement");
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
