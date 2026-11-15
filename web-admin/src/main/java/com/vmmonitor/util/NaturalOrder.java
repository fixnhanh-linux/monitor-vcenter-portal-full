package com.vmmonitor.util;

/** So sánh chuỗi kiểu Intl.Collator(numeric: true): "vm-2" đứng trước "vm-10", không phân biệt hoa thường. */
public final class NaturalOrder {
  private NaturalOrder() {}

  public static int compare(String a, String b) {
    if (a == null) a = ""; if (b == null) b = "";
    int i = 0, j = 0, na = a.length(), nb = b.length();
    while (i < na && j < nb) {
      char ca = a.charAt(i), cb = b.charAt(j);
      if (Character.isDigit(ca) && Character.isDigit(cb)) {
        int si = i, sj = j;
        while (i < na && Character.isDigit(a.charAt(i))) i++;
        while (j < nb && Character.isDigit(b.charAt(j))) j++;
        String da = a.substring(si, i).replaceFirst("^0+(?=\\d)", ""), db = b.substring(sj, j).replaceFirst("^0+(?=\\d)", "");
        if (da.length() != db.length()) return da.length() - db.length();
        int c = da.compareTo(db);
        if (c != 0) return c;
      } else {
        int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
        if (c != 0) return c;
        i++; j++;
      }
    }
    return (na - i) - (nb - j);
  }
}
