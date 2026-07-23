package com.eventguard.command.aggregate;

import com.eventguard.event.model.DomainEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateRootTest {

    // 测试用具体聚合根
    static class TestAggregate extends AggregateRoot {
        private String state;
        
        @Override
        protected void apply(DomainEvent event) {
            if (event instanceof TestEvent e) {
                setAggregateId(e.getAggregateId());
                this.state = e.payload;
            }
        }
        
        public void doSomething(UUID id) {
            raise(new TestEvent(id, getVersion() + 1, "hello"));
        }
        
        public String getState() { return state; }
    }
    
    static class TestEvent extends DomainEvent {
        final String payload;
        TestEvent(UUID aggregateId, int version, String payload) {
            super(aggregateId, version, null);
            this.payload = payload;
        }
        @Override public Object getPayload() { return Map.of("payload", payload); }
    }

    @Test
    void raise_should_add_event_to_pending_and_call_apply() {
        TestAggregate agg = new TestAggregate();
        UUID id = UUID.randomUUID();
        
        agg.doSomething(id);
        
        assertThat(agg.getState()).isEqualTo("hello");
        assertThat(agg.getAggregateId()).isEqualTo(id);
    }

    @Test
    void flushPendingEvents_should_return_events_and_clear() {
        TestAggregate agg = new TestAggregate();
        agg.doSomething(UUID.randomUUID());
        
        List<DomainEvent> events = agg.flushPendingEvents();
        
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TestEvent.class);
        assertThat(events.get(0).getVersion()).isEqualTo(1);
        // 第二次 flush 应为空
        assertThat(agg.flushPendingEvents()).isEmpty();
    }

    @Test
    void flushPendingEvents_should_update_version_to_last_event_version() {
        TestAggregate agg = new TestAggregate();
        assertThat(agg.getVersion()).isEqualTo(0);
        
        agg.doSomething(UUID.randomUUID());
        agg.flushPendingEvents();
        
        assertThat(agg.getVersion()).isEqualTo(1);
    }

    @Test
    void applyEvent_should_update_state_without_adding_to_pending() {
        TestAggregate agg = new TestAggregate();
        UUID id = UUID.randomUUID();
        TestEvent event = new TestEvent(id, 5, "replayed");
        
        agg.applyEvent(event);
        
        assertThat(agg.getState()).isEqualTo("replayed");
        assertThat(agg.getVersion()).isEqualTo(5);
        assertThat(agg.flushPendingEvents()).isEmpty();
    }
}
