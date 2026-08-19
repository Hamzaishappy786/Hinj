package com.hinj.player.download;

/**
 * Immutable snapshot of the YouTube video metadata we care about:
 * everything needed to display the preview UI and then kick off the download.
 */
public final class YtSongInfo {

    public final String title;
    public final String channelName;
    public final String thumbnailUrl;
    public final String audioStreamUrl;
    public final String fileExtension;   // e.g. "m4a"
    public final long   durationSecs;

    public YtSongInfo(String title,
                      String channelName,
                      String thumbnailUrl,
                      String audioStreamUrl,
                      String fileExtension,
                      long durationSecs) {
        this.title          = title;
        this.channelName    = channelName;
        this.thumbnailUrl   = thumbnailUrl;
        this.audioStreamUrl = audioStreamUrl;
        this.fileExtension  = fileExtension;
        this.durationSecs   = durationSecs;
    }
}
