import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// React DOM's scheduler can defer work past the test boundary; on some CI Node
// versions that deferred work touches a jsdom window that the next file's
// isolation has already torn down, surfacing as "window is not defined".
// Cleaning up after each test drains the rendered tree so no scheduler work
// remains pending when the environment is recycled.
afterEach(() => {
  cleanup();
});
