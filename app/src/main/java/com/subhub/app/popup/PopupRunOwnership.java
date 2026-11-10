package com.subhub.app.popup;

/** Serializes preview deadlines and service adoption independently of Android rendering. */
final class PopupRunOwnership {
    private enum Owner { NONE, PREVIEW, SERVICE }

    private Owner owner = Owner.NONE;
    private long generation;
    private long previewDeadline;
    private String serviceSignature = "";

    synchronized long beginPreview(long now, long durationMillis) {
        if (durationMillis <= 0) throw new IllegalArgumentException("Preview duration must be positive");
        if (owner != Owner.NONE) return 0;
        previewDeadline = Math.addExact(now, durationMillis);
        owner = Owner.PREVIEW;
        return ++generation;
    }

    synchronized long adoptService() {
        return adoptService("");
    }

    synchronized long adoptService(String signature) {
        if (signature == null) throw new IllegalArgumentException("Missing service signature");
        if (owner != Owner.SERVICE || !serviceSignature.equals(signature)) ++generation;
        owner = Owner.SERVICE;
        serviceSignature = signature;
        previewDeadline = 0;
        return generation;
    }

    synchronized long serviceToken(String signature) {
        return owner == Owner.SERVICE && serviceSignature.equals(signature) ? generation : 0;
    }

    synchronized long stopService(long token) {
        return isService(token) ? stop() : 0;
    }

    synchronized long stop() {
        owner = Owner.NONE;
        serviceSignature = "";
        previewDeadline = 0;
        return ++generation;
    }

    synchronized boolean expirePreview(long token, long now) {
        if (owner != Owner.PREVIEW || generation != token || now < previewDeadline) return false;
        stop();
        return true;
    }

    synchronized boolean isService(long token) {
        return owner == Owner.SERVICE && generation == token;
    }

    synchronized boolean isStopped(long token) {
        return owner == Owner.NONE && generation == token;
    }

    synchronized boolean hasOwner() { return owner != Owner.NONE; }
    synchronized boolean isService() { return owner == Owner.SERVICE; }
    synchronized boolean isPreview() { return owner == Owner.PREVIEW; }
    synchronized long previewToken() { return owner == Owner.PREVIEW ? generation : 0; }
    synchronized long remainingPreviewMillis(long now) {
        return owner == Owner.PREVIEW ? Math.max(0, previewDeadline - now) : 0;
    }
}
