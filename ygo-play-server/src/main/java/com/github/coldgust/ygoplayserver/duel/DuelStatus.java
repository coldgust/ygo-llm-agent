package com.github.coldgust.ygoplayserver.duel;

public enum DuelStatus {
    STARTING, RUNNING, STOPPING, STOPPED, FINISHED, FAILED;

    public boolean terminal() {
        return this == STOPPED || this == FINISHED || this == FAILED;
    }
}
