// In-game chat.
//
// Chat is handled on the server without taking the game lock, so messages
// arrive even while a shot is being resolved.

import { useEffect, useRef, useState } from "react";

const formatTime = (millis) =>
  new Date(millis ?? Date.now()).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });

export default function Chat({ log, onSend, myName, disabled }) {
  const [input, setInput] = useState("");
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [log]);

  const submit = (event) => {
    event?.preventDefault();
    const text = input.trim();
    if (!text) return;
    onSend(text);
    setInput("");
  };

  return (
    <section className="card chat">
      <h3>💬 Chat</h3>
      <div className="chat-log">
        {log.length === 0 && <p className="muted small">Say something to your opponent.</p>}
        {log.map((message, index) => (
          <div
            key={`${message.at}-${index}`}
            className={`chat-line ${message.from === myName ? "mine" : ""}`}
          >
            <span className="muted small">{formatTime(message.at)}</span>
            <strong>{message.from}</strong>
            <span className="chat-text">{message.text}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <form className="chat-input" onSubmit={submit}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={disabled ? "Chat closed" : "Type a message…"}
          maxLength={300}
          disabled={disabled}
        />
        <button type="submit" className="primary" disabled={disabled}>
          Send
        </button>
      </form>
    </section>
  );
}
