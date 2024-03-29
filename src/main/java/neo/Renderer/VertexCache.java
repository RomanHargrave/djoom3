package neo.Renderer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import neo.Renderer.Model.lightingCache_s;
import neo.Renderer.Model.shadowCache_s;
import static neo.Renderer.RenderSystem_init.r_useIndexBuffers;
import static neo.Renderer.RenderSystem_init.r_useVertexBuffers;
import neo.Renderer.VertexCache.R_ListVertexCache_f;
import static neo.Renderer.VertexCache.vertBlockTag_t.TAG_FIXED;
import static neo.Renderer.VertexCache.vertBlockTag_t.TAG_FREE;
import static neo.Renderer.VertexCache.vertBlockTag_t.TAG_TEMP;
import static neo.Renderer.VertexCache.vertBlockTag_t.TAG_USED;
import static neo.Renderer.qgl.qglBindBufferARB;
import static neo.Renderer.qgl.qglBufferDataARB;
import static neo.Renderer.qgl.qglBufferSubDataARB;
import static neo.Renderer.qgl.qglGenBuffersARB;
import static neo.Renderer.tr_local.glConfig;
import static neo.Renderer.tr_local.tr;
import neo.TempDump.TODO_Exception;
import static neo.framework.CVarSystem.CVAR_INTEGER;
import static neo.framework.CVarSystem.CVAR_RENDERER;
import neo.framework.CVarSystem.idCVar;
import static neo.framework.CmdSystem.CMD_FL_RENDERER;
import neo.framework.CmdSystem.cmdFunction_t;
import static neo.framework.CmdSystem.cmdSystem;
import static neo.framework.Common.common;
import neo.idlib.CmdArgs.idCmdArgs;
import neo.idlib.geometry.DrawVert;
import neo.idlib.geometry.DrawVert.idDrawVert;
import static neo.idlib.math.Simd.SIMDProcessor;
import neo.idlib.math.Vector.idVec3;
import neo.idlib.math.Vector.idVec4;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.ARBBufferObject.GL_STATIC_DRAW_ARB;
import static org.lwjgl.opengl.ARBBufferObject.GL_STREAM_DRAW_ARB;
import static org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB;
import static org.lwjgl.opengl.ARBVertexBufferObject.GL_ELEMENT_ARRAY_BUFFER_ARB;

/**
 *
 */
public class VertexCache {

    public static final idVertexCache vertexCache = new idVertexCache();

    // vertex cache calls should only be made by the front end
    static final int NUM_VERTEX_FRAMES = 2;

    enum vertBlockTag_t {

        TAG_FREE,
        TAG_USED,
        TAG_FIXED, // for the temp buffers
        TAG_TEMP   // in frame temp area, not static area
    };

    static class vertCache_s implements Iterable<vertCache_s> {//TODO:use iterators for all our makeshift linked lists.

        private int /*GLuint*/ vbo = 0;
        private int /*GLuint*/ vao = 0;
        private ByteBuffer     virtMem;    // only one of vbo / virtMem will be set
        private boolean        indexBuffer;// holds indexes instead of vertexes
        private int            offset;
        private int            size;       // may be larger than the amount asked for, due
        //                                 // to round up and minimum fragment sizes
        private vertBlockTag_t tag;        // a tag of 0 is a free block
        private vertCache_s[]  user;       // will be set to zero when purged
        private vertCache_s    next, prev; // may be on the static list or one of the frame lists
        private int frameUsed;             // it can't be purged if near the current frame

        @Override
        public Iterator<vertCache_s> iterator() {
            Iterator i = new Iterator() {

                @Override
                public boolean hasNext() {
                    return next != null;
                }

                @Override
                public Object next() {
                    return next;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
                }
            };

            return i;
        }

        /**
         * Creates an array starting at the current object, till it reaches
         * NULL.
         */
        public static vertCache_s[] toArray(vertCache_s cache_s) {
            List<vertCache_s> array = new ArrayList<>(10);
            Iterator<vertCache_s> iterator;

            if (cache_s != null) {
                iterator = cache_s.iterator();
                array.add(cache_s);
                while (iterator.hasNext()) {
                    array.add(iterator.next());
                }
            }

            return (vertCache_s[]) array.toArray();
        }
    }

    ;
    //
    static final int FRAME_MEMORY_BYTES = 0x200000;
    static final int EXPAND_HEADERS     = 1024;
//

    /*
     ==============
     R_ListVertexCache_f
     ==============
     */
    static class R_ListVertexCache_f extends cmdFunction_t {

        private static final cmdFunction_t instance = new R_ListVertexCache_f();

        private R_ListVertexCache_f() {
        }

        public static cmdFunction_t getInstance() {
            return instance;
        }

        @Override
        public void run(idCmdArgs args) {
            vertexCache.List();
        }
    }

    ;

    //================================================================================
    public static class idVertexCache {

        private static final idCVar r_showVertexCache  = new idCVar("r_showVertexCache", "0", CVAR_INTEGER | CVAR_RENDERER, "");
        private static final idCVar r_vertexBufferMegs = new idCVar("r_vertexBufferMegs", "32", CVAR_INTEGER | CVAR_RENDERER, "");
        //
        private       int           staticCountTotal;
        private       int           staticAllocTotal;       // for end of frame purging
        //
        private       int           staticAllocThisFrame;   // debug counter
        private       int           staticCountThisFrame;
        private       int           dynamicAllocThisFrame;
        private       int           dynamicCountThisFrame;
        //
        private       int           currentFrame;           // for purgable block tracking
        private       int           listNum;                // currentFrame % NUM_VERTEX_FRAMES, determines which tempBuffers to use
        //
        private       boolean       virtualMemory;          // not fast stuff
        //
        private       boolean       allocatingTempBuffer;   // force GL_STREAM_DRAW_ARB
        //
        private final vertCache_s[] tempBuffers;            // allocated at startup
        private       boolean       tempOverflow;           // had to alloc a temp in static memory
        //
//        private final idBlockAlloc<vertCache_s> headerAllocator = new idBlockAlloc<>(1024);
        //
        private       vertCache_s   freeStaticHeaders;      // head of doubly linked list
        private       vertCache_s   freeDynamicHeaders;     // head of doubly linked list
        private       vertCache_s   dynamicHeaders;         // head of doubly linked list
        private       vertCache_s   deferredFreeList;       // head of doubly linked list
        private       vertCache_s   staticHeaders;          // head of doubly linked list in MRU order,
        // staticHeaders.next is most recently used
        //
        private       int           frameBytes;             // for each of NUM_VERTEX_FRAMES frames
        //
        //

        public idVertexCache() {
            this.freeStaticHeaders = new vertCache_s();
            this.freeDynamicHeaders = new vertCache_s();
            this.dynamicHeaders = new vertCache_s();
            this.deferredFreeList = new vertCache_s();
            this.staticHeaders = new vertCache_s();
            this.tempBuffers = new vertCache_s[NUM_VERTEX_FRAMES];
        }

        public void Init() {
            cmdSystem.AddCommand("listVertexCache", R_ListVertexCache_f.getInstance(), CMD_FL_RENDERER, "lists vertex cache");

            if (r_vertexBufferMegs.GetInteger() < 8) {
                r_vertexBufferMegs.SetInteger(8);
            }

            virtualMemory = false;

            // use ARB_vertex_buffer_object unless explicitly disabled
            if (r_useVertexBuffers.GetInteger() != 0 && glConfig.ARBVertexBufferObjectAvailable) {
                common.Printf("using ARB_vertex_buffer_object memory\n");
            } else {
                virtualMemory = true;
                r_useIndexBuffers.SetBool(false);
                common.Printf("WARNING: vertex array range in virtual memory (SLOW)\n");
            }

            // initialize the cache memory blocks
            freeStaticHeaders.next = freeStaticHeaders.prev = freeStaticHeaders;
            staticHeaders.next = staticHeaders.prev = staticHeaders;
            freeDynamicHeaders.next = freeDynamicHeaders.prev = freeDynamicHeaders;
            dynamicHeaders.next = dynamicHeaders.prev = dynamicHeaders;
            deferredFreeList.next = deferredFreeList.prev = deferredFreeList;

            // set up the dynamic frame memory
            frameBytes = FRAME_MEMORY_BYTES;
            staticAllocTotal = 0;

            ByteBuffer junk = BufferUtils.createByteBuffer(frameBytes);// Mem_Alloc(frameBytes);
            for (int i = 0; i < NUM_VERTEX_FRAMES; i++) {
                allocatingTempBuffer = true;    // force the alloc to use GL_STREAM_DRAW_ARB
                {
                    vertCache_s[] tempBuffer = {null};
                    Alloc(junk, frameBytes, tempBuffer);
                    tempBuffers[i] = tempBuffer[0];
                }
                allocatingTempBuffer = false;
                tempBuffers[i].tag = TAG_FIXED;
                // unlink these from the static list, so they won't ever get purged
                tempBuffers[i].next.prev = tempBuffers[i].prev;
                tempBuffers[i].prev.next = tempBuffers[i].next;
            }
//            Mem_Free(junk);
            junk = null;

            EndFrame();
        }

        public void Shutdown() {
//	PurgeAll();	// !@#: also purge the temp buffers

//            headerAllocator.Shutdown();
        }

        /*
         =============
         idVertexCache::IsFast

         just for gfxinfo printing
         =============
         */
        public boolean IsFast() {
            if (virtualMemory) {
                return false;
            }
            return true;
        }

        /*
         ===========
         idVertexCache::PurgeAll

         Used when toggling vertex programs on or off, because
         the cached data isn't valid
         ===========
         */
        // called when vertex programs are enabled or disabled, because
        // the cached data is no longer valid
        public void PurgeAll() {
            while (staticHeaders.next != staticHeaders) {
                ActuallyFree(staticHeaders.next);
            }
        }

        // Tries to allocate space for the given data in fast vertex
        // memory, and copies it over.
        // Alloc does NOT do a touch, which allows purging of things
        // created at level load time even if a frame hasn't passed yet.
        // These allocations can be purged, which will zero the pointer.
        public vertCache_s[] Alloc(ByteBuffer data, int size, vertCache_s[] buffer, boolean indexBuffer /*= false*/) {
            vertCache_s block;

            if (size <= 0) {
                common.Error("idVertexCache::Alloc: size = %d\n", size);
            }

            if (null == buffer) {
                buffer = new vertCache_s[1];
            }

            // if we can't find anything, it will be NULL
            buffer[0] = null;

            // if we don't have any remaining unused headers, allocate some more
            if (freeStaticHeaders.next == freeStaticHeaders) {

                for (int i = 0; i < EXPAND_HEADERS; i++) {
                    block = new vertCache_s();//headerAllocator.Alloc();
                    block.next = freeStaticHeaders.next;
                    block.prev = freeStaticHeaders;
                    block.next.prev = block;
                    block.prev.next = block;

                    if (!virtualMemory) {
                        block.vbo = qglGenBuffersARB();
//                        block.vao = GL30.glGenVertexArrays();
                    }
                }
            }

            // move it from the freeStaticHeaders list to the staticHeaders list
            block = freeStaticHeaders.next;
            block.next.prev = block.prev;
            block.prev.next = block.next;
            block.next = staticHeaders.next;
            block.prev = staticHeaders;
            block.next.prev = block;
            block.prev.next = block;

            block.size = size;
            block.offset = 0;
            block.tag = TAG_USED;

            // save data for debugging
            staticAllocThisFrame += block.size;
            staticCountThisFrame++;
            staticCountTotal++;
            staticAllocTotal += block.size;

            // this will be set to zero when it is purged
            block.user = buffer;//TODO:wtf?
            buffer[0] = block;

            // allocation doesn't imply used-for-drawing, because at level
            // load time lots of things may be created, but they aren't
            // referenced by the GPU yet, and can be purged if needed.
            block.frameUsed = currentFrame - NUM_VERTEX_FRAMES;

            block.indexBuffer = indexBuffer;

            // copy the data
            if (block.vbo != 0) {
                if (indexBuffer) {
                    qglBindBufferARB(GL_ELEMENT_ARRAY_BUFFER_ARB, block.vbo);//TODO:get?
                    qglBufferDataARB(GL_ELEMENT_ARRAY_BUFFER_ARB, /*(GLsizeiptrARB)*/ size, data, GL_STATIC_DRAW_ARB);
                } else {
                    qglBindBufferARB(GL_ARRAY_BUFFER_ARB, block.vbo);//TODO:get?
                    if (allocatingTempBuffer) {
                        qglBufferDataARB(GL_ARRAY_BUFFER_ARB, /*(GLsizeiptrARB)*/ size, data, GL_STREAM_DRAW_ARB);
                    } else {
                        qglBufferDataARB(GL_ARRAY_BUFFER_ARB, /*(GLsizeiptrARB)*/ size, data, GL_STATIC_DRAW_ARB);
                    }
                }
            } else {
                block.virtMem = ByteBuffer.allocate(size);
//                SIMDProcessor.Memcpy(block.virtMem, data, size);
                block.virtMem = data.duplicate();
            }

            return buffer;
        }

        public void Alloc(int[] data, int size, vertCache_s buffer, boolean indexBuffer /*= false*/) {
            ByteBuffer byteData = ByteBuffer.allocate(data.length * 4);
            byteData.asIntBuffer().put(data);

//            Alloc(byteData, size, buffer, indexBuffer);
            throw new TODO_Exception();
        }

        public vertCache_s[] Alloc(ByteBuffer data, int size, vertCache_s[] buffer) {
            return Alloc(data, size, buffer, false);
        }

        public vertCache_s[] Alloc(idDrawVert[] data, int size, vertCache_s[] buffer) {
            return Alloc(DrawVert.toByteBuffer(data), size, buffer, false);
        }

        public vertCache_s Alloc(idDrawVert[] data, int size) {
            return Alloc(DrawVert.toByteBuffer(data), size, null)[0];
        }

        public void Alloc(lightingCache_s[] data, int size, vertCache_s buffer) {
            throw new TODO_Exception();
        }

        public void Alloc(shadowCache_s[] data, int size, vertCache_s buffer) {
            throw new TODO_Exception();
        }

        /*
         ==============
         idVertexCache::Position

         this will be a real pointer with virtual memory,
         but it will be an int offset cast to a pointer with
         ARB_vertex_buffer_object

         The ARB_vertex_buffer_object will be bound
         ==============
         */
        // This will be a real pointer with virtual memory,
        // but it will be an int offset cast to a pointer of ARB_vertex_buffer_object
        public ByteBuffer Position(vertCache_s buffer) {
            if (null == buffer || buffer.tag == TAG_FREE) {
                common.FatalError("idVertexCache::Position: bad vertCache_t");
            }

            // the ARB vertex object just uses an offset
            if (buffer.vbo != 0) {
                if (r_showVertexCache.GetInteger() == 2) {
                    if (buffer.tag == TAG_TEMP) {
                        common.Printf("GL_ARRAY_BUFFER_ARB = %d + %d (%d bytes)\n", buffer.vbo, buffer.offset, buffer.size);
                    } else {
                        common.Printf("GL_ARRAY_BUFFER_ARB = %d (%d bytes)\n", buffer.vbo, buffer.size);
                    }
                }
                if (buffer.indexBuffer) {
                    qglBindBufferARB(GL_ELEMENT_ARRAY_BUFFER_ARB, buffer.vbo);
                } else {
                    qglBindBufferARB(GL_ARRAY_BUFFER_ARB, buffer.vbo);
                }
                return ByteBuffer.allocate(Integer.SIZE / Byte.SIZE).putInt(buffer.offset);
            }

            // virtual memory is a real pointer
            return (ByteBuffer) buffer.virtMem.position(buffer.offset);
        }

        // if r_useIndexBuffers is enabled, but you need to draw something without
        // an indexCache, this must be called to reset GL_ELEMENT_ARRAY_BUFFER_ARB
        public void UnbindIndex() {
            qglBindBufferARB(GL_ELEMENT_ARRAY_BUFFER_ARB, 0);
        }

        /*
         ===========
         idVertexCache::AllocFrameTemp

         A frame temp allocation must never be allowed to fail due to overflow.
         We can't simply sync with the GPU and overwrite what we have, because
         there may still be future references to dynamically created surfaces.
         ===========
         */
        // automatically freed at the end of the next frame
        // used for specular texture coordinates and gui drawing, which
        // will change every frame.
        // will return NULL if the vertex cache is completely full
        // As with Position(), this may not actually be a pointer you can access.
        public vertCache_s AllocFrameTemp(ByteBuffer data, int size) {
            vertCache_s block = null;

            if (size <= 0) {
                common.Error("idVertexCache::AllocFrameTemp: size = %d\n", size);
            }

            if (dynamicAllocThisFrame + size > frameBytes) {
                // if we don't have enough room in the temp block, allocate a static block,
                // but immediately free it so it will get freed at the next frame
                tempOverflow = true;
//                Alloc(data, size, block);
                Free(block);
                return block;
            }

            // this data is just going on the shared dynamic list
            // if we don't have any remaining unused headers, allocate some more
            if (freeDynamicHeaders.next == freeDynamicHeaders) {

                for (int i = 0; i < EXPAND_HEADERS; i++) {
                    block = new vertCache_s();//headerAllocator.Alloc();
                    block.next = freeDynamicHeaders.next;
                    block.prev = freeDynamicHeaders;
                    block.next.prev = block;
                    block.prev.next = block;
                }
            }

            // move it from the freeDynamicHeaders list to the dynamicHeaders list
            block = freeDynamicHeaders.next;
            block.next.prev = block.prev;
            block.prev.next = block.next;
            block.next = dynamicHeaders.next;
            block.prev = dynamicHeaders;
            block.next.prev = block;
            block.prev.next = block;

            block.size = size;
            block.tag = TAG_TEMP;
            block.indexBuffer = false;
            block.offset = dynamicAllocThisFrame;
            dynamicAllocThisFrame += block.size;
            dynamicCountThisFrame++;
            block.user = null;
            block.frameUsed = 0;

            // copy the data
            block.virtMem = tempBuffers[listNum].virtMem;
            block.vbo = tempBuffers[listNum].vbo;

            if (block.vbo != 0) {
                qglBindBufferARB(GL_ARRAY_BUFFER_ARB, block.vbo);
                qglBufferSubDataARB(GL_ARRAY_BUFFER_ARB, block.offset, /*(GLsizeiptrARB)*/ size, data);
            } else {
                SIMDProcessor.Memcpy(block.virtMem.position(block.offset), data, size);
            }

            return block;
        }

        /*
         ===========
         idVertexCache::AllocFrameTemp

         A frame temp allocation must never be allowed to fail due to overflow.
         We can't simply sync with the GPU and overwrite what we have, because
         there may still be future references to dynamically created surfaces.
         ===========
         */
        public vertCache_s AllocFrameTemp(idDrawVert[] data, int size) {
            vertCache_s block = null;

            if (size <= 0) {
                common.Error("idVertexCache::AllocFrameTemp: size = %d\n", size);
            }

            if (dynamicAllocThisFrame + size > frameBytes) {
                // if we don't have enough room in the temp block, allocate a static block,
                // but immediately free it so it will get freed at the next frame
                tempOverflow = true;
                block = Alloc(data, size);
                Free(block);
                return block;
            }

            // this data is just going on the shared dynamic list
            // if we don't have any remaining unused headers, allocate some more
            if (freeDynamicHeaders.next == freeDynamicHeaders) {

                for (int i = 0; i < EXPAND_HEADERS; i++) {
                    block = new vertCache_s();// headerAllocator.Alloc();
                    block.next = freeDynamicHeaders.next;
                    block.prev = freeDynamicHeaders;
                    block.next.prev = block;
                    block.prev.next = block;
                }
            }

            // move it from the freeDynamicHeaders list to the dynamicHeaders list
            block = freeDynamicHeaders.next;
            block.next.prev = block.prev;
            block.prev.next = block.next;
            block.next = dynamicHeaders.next;
            block.prev = dynamicHeaders;
            block.next.prev = block;
            block.prev.next = block;

            block.size = size;
            block.tag = TAG_TEMP;
            block.indexBuffer = false;
            block.offset = dynamicAllocThisFrame;
            dynamicAllocThisFrame += block.size;
            dynamicCountThisFrame++;
            block.user = null;
            block.frameUsed = 0;

            // copy the data
            block.virtMem = tempBuffers[listNum].virtMem;
            block.vbo = tempBuffers[listNum].vbo;

            if (block.vbo != 0) {
//                GL30.glBindVertexArray(block.vao);
                qglBindBufferARB(GL_ARRAY_BUFFER_ARB, block.vbo);
                qglBufferSubDataARB(GL_ARRAY_BUFFER_ARB, block.offset, /*(GLsizeiptrARB)*/ size, DrawVert.toByteBuffer(data));
//                GL15.glBufferData(GL_ARRAY_BUFFER, DrawVert.toByteBuffer(data), GL15.GL_STATIC_DRAW);
//                GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
            } else {
                throw new TODO_Exception();
//		SIMDProcessor->Memcpy( (byte *)block->virtMem + block->offset, data, size );
//                block[0].virtMem.position(block[0].offset);
//                block[0].virtMem.put(data[0].Write());
            }

            return block;
        }

        public vertCache_s AllocFrameTemp(idVec3[] data, int size) {
            throw new TODO_Exception();
        }

        public vertCache_s AllocFrameTemp(idVec4[] data, int size) {
            throw new TODO_Exception();
        }

        // notes that a buffer is used this frame, so it can't be purged
        // out from under the GPU
        public void Touch(vertCache_s block) {
            if (null == block) {
                common.Error("idVertexCache Touch: NULL pointer");
            }

            if (block.tag == TAG_FREE) {
                common.FatalError("idVertexCache Touch: freed pointer");
            }
            if (block.tag == TAG_TEMP) {
                common.FatalError("idVertexCache Touch: temporary pointer");
            }

            block.frameUsed = currentFrame;

            // move to the head of the LRU list
            block.next.prev = block.prev;
            block.prev.next = block.next;

            block.next = staticHeaders.next;
            block.prev = staticHeaders;
            staticHeaders.next.prev = block;
            staticHeaders.next = block;
        }

        // this block won't have to zero a buffer pointer when it is purged,
        // but it must still wait for the frames to pass, in case the GPU
        // is still referencing it
        public void Free(vertCache_s block) {
            if (null == block) {
                return;
            }

            if (block.tag == TAG_FREE) {
                common.FatalError("idVertexCache Free: freed pointer");
            }
            if (block.tag == TAG_TEMP) {
                common.FatalError("idVertexCache Free: temporary pointer");
            }

            // this block still can't be purged until the frame count has expired,
            // but it won't need to clear a user pointer when it is
            block.user = null;

            block.next.prev = block.prev;
            block.prev.next = block.next;

            block.next = deferredFreeList.next;
            block.prev = deferredFreeList;
            deferredFreeList.next.prev = block;
            deferredFreeList.next = block;
        }

        // updates the counter for determining which temp space to use
        // and which blocks can be purged
        // Also prints debugging info when enabled
        public void EndFrame() {
            // display debug information
            if (r_showVertexCache.GetBool()) {
                int staticUseCount = 0;
                int staticUseSize = 0;

                for (vertCache_s block = staticHeaders.next; block != staticHeaders; block = block.next) {
                    if (block.frameUsed == currentFrame) {
                        staticUseCount++;
                        staticUseSize += block.size;
                    }
                }

                final String frameOverflow = tempOverflow ? "(OVERFLOW)" : "";

                common.Printf("vertex dynamic:%d=%dk%s, static alloc:%d=%dk used:%d=%dk total:%d=%dk\n",
                        dynamicCountThisFrame, dynamicAllocThisFrame / 1024, frameOverflow,
                        staticCountThisFrame, staticAllocThisFrame / 1024,
                        staticUseCount, staticUseSize / 1024,
                        staticCountTotal, staticAllocTotal / 1024);
            }

//if (false){
//	// if our total static count is above our working memory limit, start purging things
//	while ( staticAllocTotal > r_vertexBufferMegs.GetInteger() * 1024 * 1024 ) {
//		// free the least recently used
//
//	}
//}
            if (!virtualMemory) {
                // unbind vertex buffers so normal virtual memory will be used in case
                // r_useVertexBuffers / r_useIndexBuffers
                qglBindBufferARB(GL_ARRAY_BUFFER_ARB, 0);
                qglBindBufferARB(GL_ELEMENT_ARRAY_BUFFER_ARB, 0);
            }

            currentFrame = tr.frameCount;
            listNum = currentFrame % NUM_VERTEX_FRAMES;
            staticAllocThisFrame = 0;
            staticCountThisFrame = 0;
            dynamicAllocThisFrame = 0;
            dynamicCountThisFrame = 0;
            tempOverflow = false;

            // free all the deferred free headers
            while (deferredFreeList.next != deferredFreeList) {
                ActuallyFree(deferredFreeList.next);
            }

            // free all the frame temp headers
            vertCache_s block = dynamicHeaders.next;
            if (block != dynamicHeaders) {
                block.prev = freeDynamicHeaders;
                dynamicHeaders.prev.next = freeDynamicHeaders.next;
                freeDynamicHeaders.next.prev = dynamicHeaders.prev;
                freeDynamicHeaders.next = block;

                dynamicHeaders.next = dynamicHeaders.prev = dynamicHeaders;
            }
        }

        // listVertexCache calls this
        public void List() {
            int numActive = 0;
//            int numDeferred = 0;
            int frameStatic = 0;
            int totalStatic = 0;
//            int deferredSpace = 0;

            vertCache_s block;
            for (block = staticHeaders.next; block != staticHeaders; block = block.next) {
                numActive++;

                totalStatic += block.size;
                if (block.frameUsed == currentFrame) {
                    frameStatic += block.size;
                }
            }

            int numFreeStaticHeaders = 0;
            for (block = freeStaticHeaders.next; block != freeStaticHeaders; block = block.next) {
                numFreeStaticHeaders++;
            }

            int numFreeDynamicHeaders = 0;
            for (block = freeDynamicHeaders.next; block != freeDynamicHeaders; block = block.next) {
                numFreeDynamicHeaders++;
            }

            common.Printf("%d megs working set\n", r_vertexBufferMegs.GetInteger());
            common.Printf("%d dynamic temp buffers of %dk\n", NUM_VERTEX_FRAMES, frameBytes / 1024);
            common.Printf("%5i active static headers\n", numActive);
            common.Printf("%5i free static headers\n", numFreeStaticHeaders);
            common.Printf("%5i free dynamic headers\n", numFreeDynamicHeaders);

            if (!virtualMemory) {
                common.Printf("Vertex cache is in ARB_vertex_buffer_object memory (FAST).\n");
            } else {
                common.Printf("Vertex cache is in virtual memory (SLOW)\n");
            }

            if (RenderSystem_init.r_useIndexBuffers.GetBool()) {
                common.Printf("Index buffers are accelerated.\n");
            } else {
                common.Printf("Index buffers are not used.\n");
            }
        }

//        private void InitMemoryBlocks(int size);
        private void ActuallyFree(vertCache_s block) {
            if (null == block) {
                common.Error("idVertexCache Free: NULL pointer");
            }

            if (block.user != null) {
                // let the owner know we have purged it
                block.user = null;
            }

            // temp blocks are in a shared space that won't be freed
            if (block.tag != TAG_TEMP) {
                staticAllocTotal -= block.size;
                staticCountTotal--;

                if (block.vbo != 0) {
//                    if (false) {// this isn't really necessary, it will be reused soon enough
//                        // filling with zero length data is the equivalent of freeing
//                        qglBindBufferARB(GL_ARRAY_BUFFER_ARB, block.vbo);
//                        qglBufferDataARB(GL_ARRAY_BUFFER_ARB, 0, 0, GL_DYNAMIC_DRAW_ARB);
//                    }
                } else if (block.virtMem != null) {
//                    Mem_Free(block.virtMem);
                    block.virtMem = null;
                }
            }
            block.tag = TAG_FREE;		// mark as free

            // unlink stick it back on the free list
            block.next.prev = block.prev;
            block.prev.next = block.next;

            if (true) {
                // stick it on the front of the free list so it will be reused immediately
                block.next = freeStaticHeaders.next;
                block.prev = freeStaticHeaders;
//            } else {
//                // stick it on the back of the free list so it won't be reused soon (just for debugging)
//                block.next = freeStaticHeaders;
//                block.prev = freeStaticHeaders.prev;
            }

            block.next.prev = block;
            block.prev.next = block;
        }
    };

}
