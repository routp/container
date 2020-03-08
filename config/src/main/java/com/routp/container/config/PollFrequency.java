package com.routp.container.config;

/**
 * Polling interval modifiers. Valid values HIGH (2s), MEDIUM (10s), LOW (30s)
 *
 * @author prarout
 * @since 1.0.0
 */
public enum PollFrequency {
    HIGH(2),
    MEDIUM(10),
    LOW(30);

    private int duration;

    PollFrequency(int duration) {
        this.duration = duration;
    }

    public int getDuration() {
        return this.duration;
    }
}
