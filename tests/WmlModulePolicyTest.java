package com.wayward.launcher;

/** Dependency-free contract tests for the WML module isolation policy. */
public final class WmlModulePolicyTest {
    public static void main(String[] args) {
        check(WmlModulePolicy.isBlockedModId("imagestudio"), "Image Studio ID must be blocked");
        check(WmlModulePolicy.isBlockedModId("ImageStudio"), "module ID matching must be case-insensitive");
        check(WmlModulePolicy.isBlockedModId("  imagestudio  "), "module ID must be trimmed");
        check(!WmlModulePolicy.isBlockedModId("comfyui"), "other WML module IDs must remain available");
        check(!WmlModulePolicy.isBlockedModId(null), "null module IDs must be safe");

        check(WmlModulePolicy.isBlockedResourcePath("wml/mods/imagestudio/mod.js"), "generator entrypoint must be blocked");
        check(WmlModulePolicy.isBlockedResourcePath("/wml/mods/imagestudio/mod.js"), "leading slash must not bypass policy");
        check(WmlModulePolicy.isBlockedResourcePath("WML/MODS/IMAGESTUDIO/assets/settings.js"), "path matching must be case-insensitive");
        check(WmlModulePolicy.isBlockedResourcePath("wml\\mods\\imagestudio\\mod.js"), "Windows separators must not bypass policy");
        check(!WmlModulePolicy.isBlockedResourcePath("wml/mods/imagestudio-extra/mod.js"), "similarly named modules must remain available");
        check(!WmlModulePolicy.isBlockedResourcePath("wml/mods/other/mod.js"), "other WML module files must remain available");
        check(!WmlModulePolicy.isBlockedResourcePath("images/illustrious/characters/character.webp"), "local character image packs must remain available");
        check(!WmlModulePolicy.isBlockedResourcePath("images/characters/character.webp"), "ordinary local image packs must remain available");
        check(!WmlModulePolicy.isBlockedResourcePath(null), "null paths must be safe");
        System.out.println("WML module isolation policy tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
