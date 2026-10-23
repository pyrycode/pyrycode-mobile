package main

import (
	"context"
	"encoding/json"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/pyrycode/pyrycode/internal/history"
	"github.com/pyrycode/pyrycode/internal/protocol"
	"github.com/pyrycode/pyrycode/internal/relay"
	"github.com/pyrycode/pyrycode/internal/turnevent"
)

// Overlay-only diagnostic: the production checkout is never edited.
func TestSessionError2338PublisherDelivery(t *testing.T) {
	for _, held := range []bool{false, true} {
		name := "immediate"
		if held {
			name = "held-enumeration"
		}
		t.Run(name, func(t *testing.T) {
			o := testLiveOwner()
			src := o.capture("a", 1, history.SessionProvenance{Kind: "claude", SessionID: "a"}, true)
			entered, release := make(chan struct{}), make(chan struct{})
			var once sync.Once
			delivered := make(chan relay.LiveState, 64)
			stop := startDaemonLivePublisher(t.Context(), o, func(ctx context.Context) []relay.ActiveConn {
				if held {
					once.Do(func() {
						close(entered)
						select {
						case <-release:
						case <-ctx.Done():
						}
					})
				}
				return []relay.ActiveConn{{ConnID: "phone", Interactive: true, Thread: true}}
			}, func(_ context.Context, _ string, r relay.LiveState) error {
				delivered <- r
				return nil
			}, func(context.Context, string) { t.Error("unexpected retirement") }, discardLogger())
			defer stop()
			capture := o.acceptEvent(src, turnevent.TextChunk{Text: "probe"})
			admitted := false
			for _, r := range capture.updates {
				if r.Envelope.Type == protocol.TypeTurnState && !r.Envelope.SessionStateCleared {
					admitted = true
				}
			}
			if !admitted {
				t.Fatal("responding was not admitted")
			}
			phases := []string{}
			deadline := time.NewTimer(5 * time.Second)
			defer deadline.Stop()
			read := func() string {
				for {
					select {
					case r := <-delivered:
						if r.SessionGeneration != src.SessionGeneration {
							t.Fatal("generation changed")
						}
						if r.Envelope.Type != protocol.TypeTurnState || r.Envelope.SessionStateCleared {
							continue
						}
						var p struct {
							State string `json:"state"`
						}
						if err := json.Unmarshal(r.Envelope.Payload, &p); err != nil {
							t.Fatal(err)
						}
						phases = append(phases, p.State)
						return p.State
					case <-deadline.C:
						t.Fatal("publisher delivery did not finish")
						return ""
					}
				}
			}
			if held {
				select {
				case <-entered:
				case <-time.After(5 * time.Second):
					t.Fatal("enumeration not entered")
				}
			} else if read() != "responding" {
				t.Fatal("immediate phase was not responding")
			}
			o.acceptEvent(src, turnevent.TurnEnd{})
			if held {
				close(release)
			}
			// Held publication can deliver responding first after the release.
			// Collect through terminal idle before checking the complete sequence.
			for read() != "idle" {
			}
			t.Logf("admitted_responding=true same_generation=true delivered_phases=%v", phases)
			if !slices.Equal(phases, []string{"responding", "idle"}) {
				t.Fatalf("recovery progress suppressed: delivered phases=%v", phases)
			}
		})
	}
}
