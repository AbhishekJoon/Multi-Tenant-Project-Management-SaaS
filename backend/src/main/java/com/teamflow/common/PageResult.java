package com.teamflow.common;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
  public static int size(int size) {
    return Math.max(1, Math.min(size, 100));
  }

  public static int page(int page) {
    return Math.max(0, Math.min(page, 100000));
  }
}
