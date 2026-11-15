package com.vmmonitor.core;

import com.vmmonitor.security.Auth;

import java.util.HashSet;
import java.util.Set;

/**
 * Phạm vi dữ liệu theo người đăng nhập:
 *  khách hàng / nhân viên phụ trách: chỉ VM của các khách được gán (khách hàng còn bị ẩn thông tin hạ tầng nội bộ);
 *  nhân viên giới hạn site: chỉ VM / host / datastore / cảnh báo của các site đó. null = thấy tất cả.
 */
public record Scope(Set<String> customers, Set<String> sites, boolean hide) {
  public static Scope of(Auth.Who who) {
    if (who == null) return null;
    if ("customer".equals(who.role) || "manager".equals(who.role))
      return new Scope(new HashSet<>(who.customers == null ? java.util.List.of() : who.customers), null, "customer".equals(who.role));
    if (who.sites != null && !who.sites.isEmpty()) return new Scope(null, new HashSet<>(who.sites), false);
    return null;
  }

  public static boolean in(String customer, String site, Scope s) {
    return s == null || ((s.customers == null || s.customers.contains(customer)) && (s.sites == null || s.sites.contains(site)));
  }
}
