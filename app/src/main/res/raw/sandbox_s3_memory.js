"use strict";
const values = [];
for (let i = 0; i < 256; i += 1) values.push("x".repeat(65536));
JSON.stringify({unexpectedlyAllocated: values.length});
