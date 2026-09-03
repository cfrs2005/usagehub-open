import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

const ID_PATTERN = /^[A-Za-z0-9_-]{8,128}$/;

function validateId(value: string): string {
  if (!ID_PATTERN.test(value)) {
    throw new Error("installation id must be 8-128 URL-safe characters");
  }
  return value;
}

export function getInstallationId(stateDir: string, configured?: string): string {
  if (configured) return validateId(configured);

  const file = join(stateDir, "installation-id");
  try {
    return validateId(readFileSync(file, "utf8").trim());
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }

  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  const id = randomUUID();
  const temporary = `${file}.${process.pid}.${randomUUID()}.tmp`;
  writeFileSync(temporary, `${id}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  try {
    renameSync(temporary, file);
  } catch (error) {
    try {
      return validateId(readFileSync(file, "utf8").trim());
    } catch {
      throw error;
    }
  }
  return id;
}
