// An HTTP/1 parse probe on Go's net/http (differential.py): each request is answered with what the server took it
// to be, "METHOD TARGET len=N fnv=HEX", as http-bench's echoServer does. Usage: probe <port>
package main

import (
	"fmt"
	"hash/fnv"
	"io"
	"net/http"
	"os"
)

func main() {
	port := "3202"
	if len(os.Args) > 1 {
		port = os.Args[1]
	}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		h := fnv.New64a()
		h.Write(body)
		fmt.Fprintf(w, "%s %s len=%d fnv=%x\n", r.Method, r.RequestURI, len(body), h.Sum64())
	})
	fmt.Println("go probe on 127.0.0.1:" + port)
	if err := http.ListenAndServe("127.0.0.1:"+port, handler); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
}
