"use strict";
let sum = 0;
for (let i = 0; i < 1000; i += 1) sum += i;
const hiddenGlobals = ["java", "os", "std", "process", "require"];
const hostFree = hiddenGlobals.every((name) => typeof globalThis[name] === "undefined");
JSON.stringify({engine: "QuickJS-NG/0.17.0", proof: "signed-fixed-script", sum, hostFree});
