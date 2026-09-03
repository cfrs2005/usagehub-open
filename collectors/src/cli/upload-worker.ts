#!/usr/bin/env node
import { loadConfig } from "../shared/config.js";
import { uploadPending } from "../shared/uploader.js";

uploadPending(loadConfig()).catch(() => {
  // The finite disk queue is retried by a later statusLine event or collector poll.
  process.exitCode = 0;
});
