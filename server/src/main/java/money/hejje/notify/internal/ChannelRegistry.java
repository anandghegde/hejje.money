package money.hejje.notify.internal;

import java.util.List;
import money.hejje.notify.Channels;
import org.springframework.stereotype.Component;

@Component
class ChannelRegistry implements Channels {

    private final List<Channels.Sender> senders;

    ChannelRegistry(List<Channels.Sender> senders) {
        this.senders = List.copyOf(senders);
    }

    @Override
    public List<Channels.Sender> all() {
        return senders;
    }
}
