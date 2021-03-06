;;; -*- Mode: LISP; Syntax: COMMON-LISP; Package: CL-USER; Base: 10 -*-
;;; $Header: /usr/local/cvsrep/cl-unicode/cl-unicode.asd,v 1.22 2008/07/24 14:56:31 edi Exp $

;;; Copyright (c) 2008, Dr. Edmund Weitz.  All rights reserved.

;;; Redistribution and use in source and binary forms, with or without
;;; modification, are permitted provided that the following conditions
;;; are met:

;;;   * Redistributions of source code must retain the above copyright
;;;     notice, this list of conditions and the following disclaimer.

;;;   * Redistributions in binary form must reproduce the above
;;;     copyright notice, this list of conditions and the following
;;;     disclaimer in the documentation and/or other materials
;;;     provided with the distribution.

;;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR 'AS IS' AND ANY EXPRESSED
;;; OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
;;; WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
;;; ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
;;; DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
;;; DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
;;; GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
;;; INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
;;; WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
;;; NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
;;; SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(in-package :cl-user)

(defpackage :cl-unicode-asd
  (:use :cl :asdf))

(in-package :cl-unicode-asd)

(defsystem :build-cl-unicode
  :serial t
  ;; FLEXI-STREAMS is only needed to /build/ CL-UNICODE
  :depends-on (:flexi-streams)
  :components  ((:file "packages")
                (:file "specials")
                (:file "util")
                (:module "build"
                 :serial t
                 :components ((:file "util")
                              (:file "char-info")
                              (:file "read")
                              (:file "dump")))))

(defclass generated-cl-source-file (cl-source-file)
  ()
  (:documentation "A subclass of CL-SOURCE-FILE for source files which
might have to be generated by loading the BUILD-CL-UNICODE system
first."))

(defmethod perform ((operation compile-op) (component generated-cl-source-file))
  "A method which makes sure that the files of type
GENERATED-CL-SOURCE-FILE actually exist before we try to compile
them."
  (unless (every 'probe-file (input-files operation component))
    (operate 'load-op :build-cl-unicode))
  (call-next-method))

(defsystem :cl-unicode
  :version "0.1.1"
  :serial t
  :depends-on (:cl-ppcre)
  :components ((:file "packages")
               (:file "specials")
               (:file "util")
               (:file "conditions")
               (:generated-cl-source-file "lists")
               (:generated-cl-source-file "hash-tables")
               (:file "api")
               (:generated-cl-source-file "methods")
               (:file "test-functions")
               (:file "derived")
               (:file "alias")))
               
(defsystem :cl-unicode-test
  :depends-on (:cl-unicode)
  :components ((:module "test"
                        :serial t
                        :components ((:file "packages")
                                     (:file "tests")))))

(defmethod perform ((o test-op) (c (eql (find-system :cl-unicode))))
  (operate 'load-op :cl-unicode-test)
  (funcall (intern (symbol-name :run-all-tests) (find-package :cl-unicode-test))))
