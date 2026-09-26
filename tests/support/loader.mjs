// Lets plain Node import the app's bundler-style extensionless specifiers,
// so the protocol test can use the real client reducer without a build step.
import { existsSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, resolve as resolvePath } from "node:path";

const REACT_SHIM = new URL("./react-shim.mjs", import.meta.url).href;

export async function resolve(specifier, context, nextResolve) {
  if (specifier === "react") {
    return { url: REACT_SHIM, shortCircuit: true };
  }
  if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
    const base = dirname(fileURLToPath(context.parentURL));
    for (const ext of [".js", ".jsx", ".mjs"]) {
      const candidate = resolvePath(base, specifier + ext);
      if (existsSync(candidate)) {
        return { url: pathToFileURL(candidate).href, shortCircuit: true };
      }
    }
  }
  return nextResolve(specifier, context);
}
