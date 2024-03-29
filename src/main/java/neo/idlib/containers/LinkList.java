package neo.idlib.containers;

/**
 * //TODO:implement starcraft linked list
 */
public class LinkList {
    /*
     ==============================================================================

     idLinkList

     Circular linked list template

     ==============================================================================
     */

    public static class idLinkList<type> {

        private idLinkList head;
        private idLinkList next;
        private idLinkList prev;
        private type owner;
        //
        //

        /*
         ================
         idLinkList<type>::idLinkList

         Node is initialized to be the head of an empty list
         ================
         */
        public idLinkList() {
            owner = null;
            head = this;
            next = this;
            prev = this;
        }
//public						~idLinkList();
//

        /*
         ================
         idLinkList<type>::IsListEmpty

         Returns true if the list is empty.
         ================
         */
        public boolean IsListEmpty() {
            return head.next == head;
        }

        /*
         ================
         idLinkList<type>::InList

         Returns true if the node is in a list.  If called on the head of a list, will always return false.
         ================
         */
        public boolean InList() {
            return head != this;
        }

        /*
         ================
         idLinkList<type>::Num

         Returns the number of nodes in the list.
         ================
         */
        public int Num() {
            idLinkList<type> node;
            int num;

            num = 0;
            for (node = head.next; node != head; node = node.next) {
                num++;
            }

            return num;
        }

        /*
         ================
         idLinkList<type>::Clear

         If node is the head of the list, clears the list.  Otherwise it just removes the node from the list.
         ================
         */
        public void Clear() {
            if (head == this) {
                while (next != this) {
                    next.Remove();
                }
            } else {
                Remove();
            }
        }
//

        /*
         ================
         idLinkList<type>::InsertBefore

         Places the node before the existing node in the list.  If the existing node is the head,
         then the new node is placed at the end of the list.
         ================
         */
        public void InsertBefore(idLinkList node) {
            Remove();

            next = node;
            prev = node.prev;
            node.prev = this;
            prev.next = this;
            head = node.head;
        }

        /*
         ================
         idLinkList<type>::InsertAfter

         Places the node after the existing node in the list.  If the existing node is the head,
         then the new node is placed at the beginning of the list.
         ================
         */
        public void InsertAfter(idLinkList node) {
            Remove();

            prev = node;
            next = node.next;
            node.next = this;
            next.prev = this;
            head = node.head;
        }

        /*
         ================
         idLinkList<type>::AddToEnd

         Adds node at the end of the list
         ================
         */
        public void AddToEnd(idLinkList node) {
            InsertBefore(node.head);
        }

        /*
         ================
         idLinkList<type>::AddToFront

         Adds node at the beginning of the list
         ================
         */
        public void AddToFront(idLinkList node) {
            InsertAfter(node.head);
        }
//

        /*
         ================
         idLinkList<type>::Remove

         Removes node from list
         ================
         */
        public void Remove() {
            prev.next = next;
            next.prev = prev;

            next = this;
            prev = this;
            head = this;
        }
//

        /*
         ================
         idLinkList<type>::Next

         Returns the next object in the list, or NULL if at the end.
         ================
         */
        public type Next() {
            if (null == next || (next == head)) {
                return null;
            }
            return (type) next.owner;
        }

        /*
         ================
         idLinkList<type>::Prev

         Returns the previous object in the list, or NULL if at the beginning.
         ================
         */
        public type Prev() {
            if (null == prev || (prev == head)) {
                return null;
            }
            return (type) prev.owner;
        }
//

        /*
         ================
         idLinkList<type>::Owner

         Gets the object that is associated with this node.
         ================
         */
        public type Owner() {
            return owner;
        }

        /*
         ================
         idLinkList<type>::SetOwner

         Sets the object that this node is associated with.
         ================
         */
        public void SetOwner(type object) {
            owner = object;
        }
//

        /*
         ================
         idLinkList<type>::ListHead

         Returns the head of the list.  If the node isn't in a list, it returns
         a pointer to itself.
         ================
         */
        public idLinkList ListHead() {
            return head;
        }

        /*
         ================
         idLinkList<type>::NextNode

         Returns the next node in the list, or NULL if at the end.
         ================
         */
        public idLinkList NextNode() {
            if (next == head) {
                return null;
            }
            return next;
        }

        /*
         ================
         idLinkList<type>::PrevNode

         Returns the previous node in the list, or NULL if at the beginning.
         ================
         */
        public idLinkList PrevNode() {
            if (prev == head) {
                return null;
            }
            return prev;
        }
    };
}
