hacking
---


notes
===

* The REPL spawns automatically when you open a file. This is bad. A
  singleton REPL needs to be controllably brought up/down.

* It's tied to SWANK, which in turn is distressingly tied to a
  particular implementation's _implementation_ of SWANK.

* cusp is delivered with a big pile of libraries (it's pre-Quicklisp).

* cusp is also delivered with an old version of SBCL for OSX/Linux/Windows.


directions
===

* decouple the repl better

* strip out libs, add quicklisp

inferior-lisp work
===
sbcl 1.2.9 and SLIME 2.13 will load together on emacs on OSX.

The command issued to the sbcl is....

    (progn
      (load "/Users/<username>/.emacs.d/plugins/slime/swank-loader.lisp" :verbose t)
      (funcall (read-from-string "swank-loader:init"))
      (funcall (read-from-string "swank:start-server") "temp-folder"))
