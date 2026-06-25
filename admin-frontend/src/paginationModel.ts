export type PageItem = number | { type: "ellipsis"; page: number };

export function paginationItems(current: number, total: number): PageItem[] {
  if (total <= 11) return Array.from({ length: total }, (_, index) => index + 1);
  const safeCurrent = Math.max(1, Math.min(total, current));
  const pages = new Set<number>([
    1,
    2,
    3,
    safeCurrent - 1,
    safeCurrent,
    safeCurrent + 1,
    total - 2,
    total - 1,
    total,
  ]);
  const sorted = Array.from(pages).filter((page) => page >= 1 && page <= total).sort((a, b) => a - b);
  return sorted.flatMap((page, index) => {
    const previous = sorted[index - 1];
    if (previous && page - previous > 1) {
      return [{ type: "ellipsis" as const, page: Math.floor((previous + page) / 2) }, page];
    }
    return [page];
  });
}
