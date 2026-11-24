// Stand-in for Cloudflare's cloudflare:sockets in worker.test.mjs.
export let opened = [];
let options = {};

export function reset(opts = {}) {
  opened = [];
  options = opts;
}

export function connect(address) {
  let replyController;
  let openNow;
  const socket = {
    address,
    written: [],
    readable: new ReadableStream({
      start(controller) {
        replyController = controller;
      },
    }),
    writable: new WritableStream({
      write(chunk) {
        if (options.stallWrites) return new Promise(() => {});
        socket.written.push(...chunk);
      },
    }),
    opened: options.failOpen ? Promise.reject(new Error("refused"))
      : options.delayOpen ? new Promise((resolve) => { openNow = resolve; }) : Promise.resolve({}),
    open() {
      openNow({});
    },
    reply(bytes) {
      replyController.enqueue(bytes);
    },
    end() {
      replyController.close();
    },
    close() {},
  };
  socket.opened.catch(() => {});
  opened.push(socket);
  return socket;
}
