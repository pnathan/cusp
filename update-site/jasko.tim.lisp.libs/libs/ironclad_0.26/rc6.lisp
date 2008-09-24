;;;; rc6.lisp -- implementation of the RC6 block cipher

(in-package :ironclad)

(defconstant +rc6/32-p+ #xb7e15163)
(defconstant +rc6/32-q+ #x9e3779b9)

(deftype rc6-n-rounds () '(mod 256))

(defclass rc6 (cipher 16-byte-block-mixin)
  ((n-rounds :reader n-rounds :initarg :n-rounds :type rc6-n-rounds)
   (round-keys :accessor round-keys
               :type (simple-array (unsigned-byte 32) (*))))
  (:default-initargs :n-rounds 20))

;;; The code generated by these functions produces lots of compiler
;;; notes under SBCL, but the resulting disassembly looks pretty good.
;;; I don't know where the notes originate from...
(eval-when (:compile-toplevel :load-toplevel :execute)
(defun generate-unrolled-rc6-encryption (n-rounds)
  (let* ((orig-vars (list 'a 'b 'c 'd))
         (vars (setf (cdr (last orig-vars)) orig-vars)))
    (loop for i from 2 upto (* n-rounds 2) by 2
          for (a b c d) on vars by #'cdr
          collect `(let ((v (rol32 (mod32* ,b
                                           #+(and sbcl x86)
                                           (ldb (byte 32 0) (sb-vm::%lea ,b ,b 1 1))
                                           #-(and sbcl x86)
                                           (mod32+ (mod32ash ,b 1) 1)) 5))
                         (u (rol32 (mod32* ,d
                                           #+(and sbcl x86)
                                           (ldb (byte 32 0) (sb-vm::%lea ,d ,d 1 1))
                                           #-(and sbcl x86)
                                           (mod32+ (mod32ash ,d 1) 1)) 5)))
                    (declare (type (unsigned-byte 32) u v))
                    (setf ,a (mod32+ (rol32 (logxor ,a v) (mod u 32))
                              (aref round-keys ,i))
                     ,c (mod32+ (rol32 (logxor ,c u) (mod v 32))
                         (aref round-keys ,(1+ i))))) into forms
          finally (return `(let ((round-keys (round-keys context)))
                            (declare (type (simple-array (unsigned-byte 32) (,(+ (* n-rounds 2) 4))) round-keys))
                            (with-words ((a b c d) plaintext plaintext-start :big-endian nil)
                              (setf b (mod32+ b (aref round-keys 0))
                                    d (mod32+ d (aref round-keys 1)))
                              ,@forms
                              (setf a (mod32+ a (aref round-keys (+ (* 2 ,n-rounds) 2)))
                                    c (mod32+ c (aref round-keys (+ (* 2 ,n-rounds) 3))))
                              (store-words ciphertext ciphertext-start a b c d)))))))

(defun generate-unrolled-rc6-decryption (n-rounds)
  (let* ((orig-vars (list 'd 'a 'b 'c))
         (vars (setf (cdr (last orig-vars)) orig-vars)))
    (loop for i from (* n-rounds 2) downto 2 by 2
          for (a b c d) on vars by #'cdddr
          collect `(let ((u (rol32 (mod32* ,d
                                           #+(and sbcl x86)
                                           (ldb (byte 32 0) (sb-vm::%lea ,d ,d 1 1))
                                           #-(and sbcl x86)
                                           (mod32+ (mod32ash ,d 1) 1)) 5))
                         (v (rol32 (mod32* ,b
                                           #+(and sbcl x86)
                                           (ldb (byte 32 0) (sb-vm::%lea ,b ,b 1 1))
                                           #-(and sbcl x86)
                                           (mod32+ (mod32ash ,b 1) 1)) 5)))
                    (declare (type (unsigned-byte 32) u v))
                    (setf ,c (logxor (ror32 (mod32- ,c (aref round-keys ,(1+ i)))
                                            (mod v 32)) u)
                          ,a (logxor (ror32 (mod32- ,a (aref round-keys ,i))
                                            (mod u 32)) v))) into forms
        finally (return `(let ((round-keys (round-keys context)))
                          (declare (type (simple-array (unsigned-byte 32) (,(+ (* n-rounds 2) 4))) round-keys))
                          (with-words ((a b c d) ciphertext ciphertext-start :big-endian nil)
                            (setf c (mod32- c (aref round-keys (+ (* 2 ,n-rounds) 3)))
                                  a (mod32- a (aref round-keys (+ (* 2 ,n-rounds) 2))))
                            ,@forms
                            (setf d (mod32- d (aref round-keys 1))
                                  b (mod32- b (aref round-keys 0)))
                            (store-words plaintext plaintext-start a b c d)))))))
) ; EVAL-WHEN

(define-block-encryptor rc6 16
  #.(generate-unrolled-rc6-encryption 20))

(define-block-decryptor rc6 16
  #.(generate-unrolled-rc6-decryption 20))

(defun rc6-expand-key (key n-rounds)
  (declare (type (simple-array (unsigned-byte 8) (*)) key))
  (declare (type rc6-n-rounds n-rounds))
  (let* ((n-round-keys (* 2 (+ n-rounds 2)))
         (round-keys (make-array n-round-keys :element-type '(unsigned-byte 32)))
         (expanded-key (make-array 256 :element-type '(unsigned-byte 8)
                                     :initial-element 0))
         (n-expanded-key-words (ceiling (length key) 4))
         (l (make-array 64 :element-type '(unsigned-byte 32))))
    (declare (dynamic-extent expanded-key l))
    (declare (type (simple-array (unsigned-byte 8) (256)) expanded-key))
    (declare (type (simple-array (unsigned-byte 32) (64)) l))
    (declare (type (simple-array (unsigned-byte 32) (*)) round-keys))
    ;; convert the key into a sequence of (unsigned-byte 32).  this way
    ;; is somewhat slow and consy, but it's easily shown to be correct.
    (replace expanded-key key)
    (loop for i from 0 below 64 do
          (setf (aref l i) (ub32ref/le expanded-key (* i 4))))
    ;; initialize the round keys
    (loop initially (setf (aref round-keys 0) +rc6/32-p+)
      for i from 1 below n-round-keys do
      (setf (aref round-keys i) (mod32+ (aref round-keys (1- i)) +rc6/32-q+)))
    ;; mix in the user's key
    (do ((k (* 3 (max n-expanded-key-words n-round-keys)) (1- k))
         (a 0)
         (b 0)
         (i 0 (mod (1+ i) n-round-keys))
         (j 0 (mod (1+ j) n-expanded-key-words)))
        ((<= k 0) round-keys)
      (declare (type (unsigned-byte 32) a b))
      (setf a (rol32 (mod32+ (aref round-keys i) (mod32+ a b)) 3))
      (setf (aref round-keys i) a)
      (setf b (let ((x (mod32+ a b)))
                (declare (type (unsigned-byte 32) x))
                (rol32 (mod32+ (aref l j) x) (mod x 32))))
      (setf (aref l j) b))))

(defmethod schedule-key ((cipher rc6) key)
  (let* ((n-rounds (n-rounds cipher))
         (round-keys (rc6-expand-key key n-rounds)))
    (setf (round-keys cipher) round-keys)
    cipher))

(defcipher rc6
  (:encrypt-function rc6-encrypt-block)
  (:decrypt-function rc6-decrypt-block)
  (:block-length 16)
  (:key-length (:variable 1 255 1)))
