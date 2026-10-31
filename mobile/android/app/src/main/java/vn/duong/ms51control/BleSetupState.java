package vn.duong.ms51control;

/** Serializes asynchronous bond/service/MTU events, including late duplicates. */
final class BleSetupState {
    enum Stage { CONNECTING, BONDING, DISCOVERING, MTU, SUBSCRIBING, READY, CLOSED }
    private Stage stage=Stage.CONNECTING;
    synchronized boolean connected(boolean bonded) {
        if(stage!=Stage.CONNECTING)return false;
        stage=bonded?Stage.DISCOVERING:Stage.BONDING;return bonded;
    }
    synchronized boolean bonded() {
        if(stage!=Stage.BONDING)return false;
        stage=Stage.DISCOVERING;return true;
    }
    synchronized boolean services() {
        if(stage!=Stage.DISCOVERING)return false;
        stage=Stage.MTU;return true;
    }
    synchronized boolean subscribe() {
        if(stage!=Stage.MTU)return false;
        stage=Stage.SUBSCRIBING;return true;
    }
    synchronized boolean ready() {
        if(stage!=Stage.SUBSCRIBING)return false;
        stage=Stage.READY;return true;
    }
    synchronized boolean isReady(){return stage==Stage.READY;}
    synchronized Stage stage(){return stage;}
    synchronized void close(){stage=Stage.CLOSED;}
}
