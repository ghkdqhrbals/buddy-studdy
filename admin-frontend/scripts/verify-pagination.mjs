function paginationItems(current, total) {
  if (total <= 11) return Array.from({ length: total }, (_, index) => index + 1);
  const safeCurrent = Math.max(1, Math.min(total, current));
  const pages = new Set([
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
      return [{ type: "ellipsis", page: Math.floor((previous + page) / 2) }, page];
    }
    return [page];
  });
}

function visible(items) {
  return items.map((item) => typeof item === "number" ? String(item) : "...").join(" ");
}

function assertEqual(name, actual, expected) {
  if (actual !== expected) {
    throw new Error(`${name}: expected ${expected}, got ${actual}`);
  }
}

function assertPages(name, current, total, expectedVisible, expectedJumps) {
  const items = paginationItems(current, total);
  assertEqual(`${name} visible`, visible(items), expectedVisible);
  assertEqual(`${name} jumps`, JSON.stringify(items.filter((item) => typeof item !== "number").map((item) => item.page)), JSON.stringify(expectedJumps));
}

assertPages("small total", 1, 5, "1 2 3 4 5", []);
assertPages("large first page", 1, 134, "1 2 3 ... 132 133 134", [67]);
assertPages("large middle page", 67, 134, "1 2 3 ... 66 67 68 ... 132 133 134", [34, 100]);
assertPages("large last page", 134, 134, "1 2 3 ... 132 133 134", [67]);
console.log("pagination verified");
