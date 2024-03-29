package neo.Game;

import java.nio.ByteBuffer;
import static neo.Game.Game_local.gameLocal;
import static neo.Game.Game_local.gameRenderWorld;
import neo.Game.SmokeParticles.activeSmokeStage_t;
import neo.Game.SmokeParticles.idSmokeParticles;
import neo.Game.SmokeParticles.singleSmoke_t;
import neo.Renderer.Model.modelSurface_s;
import neo.Renderer.Model.srfTriangles_s;
import static neo.Renderer.ModelManager.renderModelManager;
import static neo.Renderer.RenderWorld.SHADERPARM_BLUE;
import static neo.Renderer.RenderWorld.SHADERPARM_GREEN;
import static neo.Renderer.RenderWorld.SHADERPARM_RED;
import neo.Renderer.RenderWorld.deferredEntityCallback_t;
import neo.Renderer.RenderWorld.renderEntity_s;
import neo.Renderer.RenderWorld.renderView_s;
import neo.framework.DeclParticle.idDeclParticle;
import neo.framework.DeclParticle.idParticleStage;
import neo.framework.DeclParticle.particleGen_t;
import static neo.framework.UsercmdGen.USERCMD_MSEC;
import neo.idlib.containers.List.idList;
import neo.idlib.math.Matrix.idMat3;
import static neo.idlib.math.Matrix.idMat3.mat3_identity;
import neo.idlib.math.Random.idRandom;
import neo.idlib.math.Vector.idVec3;

/**
 *
 */
public class SmokeParticles {

    /*
     ===============================================================================

     Smoke systems are for particles that are emitted off of things that are
     constantly changing position and orientation, like muzzle smoke coming
     from a bone on a weapon, blood spurting from a wound, or particles
     trailing from a monster limb.

     The smoke particles are always evaluated and rendered each tic, so there
     is a performance cost with using them for continuous effects. The general
     particle systems are completely parametric, and have no performance
     overhead when not in view.

     All smoke systems share the same shaderparms, so any coloration must be
     done in the particle definition.

     Each particle model has its own shaderparms, which can be used by the
     particle materials.

     ===============================================================================
     */
    public static class singleSmoke_t {

        singleSmoke_t next;
        int privateStartTime;	// start time for this particular particle
        int index;				// particle index in system, 0 <= index < stage->totalParticles
        idRandom random;
        idVec3 origin;
        idMat3 axis;
    };

    public static class activeSmokeStage_t {

        idParticleStage stage;
        singleSmoke_t smokes;
    };
    public static final String smokeParticle_SnapshotName = "_SmokeParticle_Snapshot_";

    public static class idSmokeParticles {

        private boolean        initialized;
        //
        private renderEntity_s renderEntity;              // used to present a model to the renderer
        private int            renderEntityHandle;        // handle to static renderer model
        //
        private static final int             MAX_SMOKE_PARTICLES = 10000;
        private              singleSmoke_t[] smokes              = new singleSmoke_t[MAX_SMOKE_PARTICLES];
        //
        private idList<activeSmokeStage_t> activeStages;
        private singleSmoke_t              freeSmokes;
        private int                        numActiveSmokes;
        private int                        currentParticleTime;    // don't need to recalculate if == view time
        //
        //

        public idSmokeParticles() {
            initialized = false;
//	memset( &renderEntity, 0, sizeof( renderEntity ) );
            renderEntity = new renderEntity_s();
            renderEntityHandle = -1;
//	memset( smokes, 0, sizeof( smokes ) );
            freeSmokes = null;
            numActiveSmokes = 0;
            currentParticleTime = -1;
        }

        // creats an entity covering the entire world that will call back each rendering
        public void Init() {
            if (initialized) {
                Shutdown();
            }

            // set up the free list
            for (int i = 0; i < MAX_SMOKE_PARTICLES - 1; i++) {
                smokes[i].next = smokes[i + 1];
            }
            smokes[MAX_SMOKE_PARTICLES - 1].next = null;
            freeSmokes = smokes[0];
            numActiveSmokes = 0;

            activeStages.Clear();

//	memset( &renderEntity, 0, sizeof( renderEntity ) );
            renderEntity = new renderEntity_s();

            renderEntity.bounds.Clear();
            renderEntity.axis = mat3_identity;
            renderEntity.shaderParms[ SHADERPARM_RED] = 1;
            renderEntity.shaderParms[ SHADERPARM_GREEN] = 1;
            renderEntity.shaderParms[ SHADERPARM_BLUE] = 1;
            renderEntity.shaderParms[3] = 1;

            renderEntity.hModel = renderModelManager.AllocModel();
            renderEntity.hModel.InitEmpty(smokeParticle_SnapshotName);

            // we certainly don't want particle shadows
            renderEntity.noShadow = true;//1;

            // huge bounds, so it will be present in every world area
            renderEntity.bounds.AddPoint(new idVec3(-100000, -100000, -100000));
            renderEntity.bounds.AddPoint(new idVec3(100000, 100000, 100000));

            renderEntity.callback = idSmokeParticles.ModelCallback.getInstance();
            // add to renderer list
            renderEntityHandle = gameRenderWorld.AddEntityDef(renderEntity);

            currentParticleTime = -1;

            initialized = true;
        }

        public void Shutdown() {
            // make sure the render entity is freed before the model is freed
            if (renderEntityHandle != -1) {
                gameRenderWorld.FreeEntityDef(renderEntityHandle);
                renderEntityHandle = -1;
            }
            if (renderEntity.hModel != null) {
                renderModelManager.FreeModel(renderEntity.hModel);
                renderEntity.hModel = null;
            }
            initialized = false;
        }

        /*
         ================
         idSmokeParticles::EmitSmoke

         Called by game code to drop another particle into the list
         ================
         */
        // spits out a particle, returning false if the system will not emit any more particles in the future
        public boolean EmitSmoke(final idDeclParticle smoke, final int systemStartTime, final float diversity, final idVec3 origin, final idMat3 axis) {
            boolean continues = false;

            if (null == smoke) {
                return false;
            }

            if (!gameLocal.isNewFrame) {
                return false;
            }

            // dedicated doesn't smoke. No UpdateRenderEntity, so they would not be freed
            if (gameLocal.localClientNum < 0) {
                return false;
            }

            assert (gameLocal.time == 0 || systemStartTime <= gameLocal.time);
            if (systemStartTime > gameLocal.time) {
                return false;
            }

            idRandom steppingRandom = new idRandom((int) (0xffff * diversity));

            // for each stage in the smoke that is still emitting particles, emit a new singleSmoke_t
            for (int stageNum = 0; stageNum < smoke.stages.Num(); stageNum++) {
                final idParticleStage stage = smoke.stages.oGet(stageNum);

                if (0 == stage.cycleMsec) {
                    continue;
                }

                if (null == stage.material) {
                    continue;
                }

                if (stage.particleLife <= 0) {
                    continue;
                }

                // see how many particles we should emit this tic
                // FIXME: 			smoke.privateStartTime += stage.timeOffset;
                int finalParticleTime = (int) (stage.cycleMsec * stage.spawnBunching);
                int deltaMsec = gameLocal.time - systemStartTime;

                int nowCount = 0, prevCount;
                if (finalParticleTime == 0) {
                    // if spawnBunching is 0, they will all come out at once
                    if (gameLocal.time == systemStartTime) {
                        prevCount = -1;
                        nowCount = stage.totalParticles - 1;
                    } else {
                        prevCount = stage.totalParticles;
                    }
                } else {
                    nowCount = (int) Math.floor(((float) deltaMsec / finalParticleTime) * stage.totalParticles);
                    if (nowCount >= stage.totalParticles) {
                        nowCount = stage.totalParticles - 1;
                    }
                    prevCount = (int) Math.floor(((float) (deltaMsec - USERCMD_MSEC) / finalParticleTime) * stage.totalParticles);
                    if (prevCount < -1) {
                        prevCount = -1;
                    }
                }

                if (prevCount >= stage.totalParticles) {
                    // no more particles from this stage
                    continue;
                }

                if (nowCount < stage.totalParticles - 1) {
                    // the system will need to emit particles next frame as well
                    continues = true;
                }

                // find an activeSmokeStage that matches this
                activeSmokeStage_t active = new activeSmokeStage_t();
                int i;
                for (i = 0; i < activeStages.Num(); i++) {
                    active = activeStages.oGet(i);
                    if (active.stage == stage) {
                        break;
                    }
                }
                if (i == activeStages.Num()) {
                    // add a new one
                    activeSmokeStage_t newActive = new activeSmokeStage_t();

                    newActive.smokes = null;
                    newActive.stage = stage;
                    i = activeStages.Append(newActive);
                    active = activeStages.oGet(i);
                }

                // add all the required particles
                for (prevCount++; prevCount <= nowCount; prevCount++) {
                    if (null == freeSmokes) {
                        gameLocal.Printf("idSmokeParticles::EmitSmoke: no free smokes with %d active stages\n", activeStages.Num());
                        return true;
                    }
                    singleSmoke_t newSmoke = freeSmokes;
                    freeSmokes = freeSmokes.next;
                    numActiveSmokes++;

                    newSmoke.index = prevCount;
                    newSmoke.axis = axis;
                    newSmoke.origin = origin;
                    newSmoke.random = steppingRandom;
                    newSmoke.privateStartTime = systemStartTime + prevCount * finalParticleTime / stage.totalParticles;
                    newSmoke.next = active.smokes;
                    active.smokes = newSmoke;

                    steppingRandom.RandomInt();	// advance the random
                }
            }

            return continues;
        }

        // free old smokes
        public void FreeSmokes() {
            for (int activeStageNum = 0; activeStageNum < activeStages.Num(); activeStageNum++) {
                singleSmoke_t smoke, next, last;

                activeSmokeStage_t active = activeStages.oGet(activeStageNum);
                final idParticleStage stage = active.stage;

                for (last = null, smoke = active.smokes; smoke != null; smoke = next) {
                    next = smoke.next;

                    float frac = (float) (gameLocal.time - smoke.privateStartTime) / (stage.particleLife * 1000);
                    if (frac >= 1.0f) {
                        // remove the particle from the stage list
                        if (last != null) {
                            last.next = smoke.next;
                        } else {
                            active.smokes = smoke.next;
                        }
                        // put the particle on the free list
                        smoke.next = freeSmokes;
                        freeSmokes = smoke;
                        numActiveSmokes--;
                        continue;
                    }

                    last = smoke;
                }

                if (null == active.smokes) {
                    // remove this from the activeStages list
                    activeStages.RemoveIndex(activeStageNum);
                    activeStageNum--;
                }
            }
        }

        private boolean UpdateRenderEntity(renderEntity_s renderEntity, final renderView_s renderView) {

            // FIXME: re-use model surfaces
            renderEntity.hModel.InitEmpty(smokeParticle_SnapshotName);

            // this may be triggered by a model trace or other non-view related source,
            // to which we should look like an empty model
            if (null == renderView) {
                return false;
            }

            // don't regenerate it if it is current
            if (renderView.time == currentParticleTime && !renderView.forceUpdate) {
                return false;
            }
            currentParticleTime = renderView.time;

            particleGen_t g = new particleGen_t();

            g.renderEnt = renderEntity;
            g.renderView = renderView;

            for (int activeStageNum = 0; activeStageNum < activeStages.Num(); activeStageNum++) {
                singleSmoke_t smoke, next, last;

                activeSmokeStage_t active = activeStages.oGet(activeStageNum);
                final idParticleStage stage = active.stage;

                if (null == stage.material) {
                    continue;
                }

                // allocate a srfTriangles that can hold all the particles
                int count = 0;
                for (smoke = active.smokes; smoke != null; smoke = smoke.next) {
                    count++;
                }
                int quads = count * stage.NumQuadsPerParticle();
                srfTriangles_s tri = renderEntity.hModel.AllocSurfaceTriangles(quads * 4, quads * 6);
                tri.numIndexes = quads * 6;
                tri.numVerts = quads * 4;

                // just always draw the particles
                tri.bounds.oSet(0, 0,
                        tri.bounds.oSet(0, 1,
                                tri.bounds.oSet(0, 2, -99999)));
                tri.bounds.oSet(1, 0,
                        tri.bounds.oSet(1, 1,
                                tri.bounds.oSet(1, 2, 99999)));

                tri.numVerts = 0;
                for (last = null, smoke = active.smokes; smoke != null; smoke = next) {
                    next = smoke.next;

                    g.frac = (float) (gameLocal.time - smoke.privateStartTime) / (stage.particleLife * 1000);
                    if (g.frac >= 1.0f) {
                        // remove the particle from the stage list
                        if (last != null) {
                            last.next = smoke.next;
                        } else {
                            active.smokes = smoke.next;
                        }
                        // put the particle on the free list
                        smoke.next = freeSmokes;
                        freeSmokes = smoke;
                        numActiveSmokes--;
                        continue;
                    }

                    g.index = smoke.index;
                    g.random = smoke.random;

                    g.origin = smoke.origin;
                    g.axis = smoke.axis;

                    g.originalRandom = g.random;
                    g.age = g.frac * stage.particleLife;

                    tri.numVerts += stage.CreateParticle(g, tri.verts, tri.numVerts);

                    last = smoke;
                }
                if (tri.numVerts > quads * 4) {
                    gameLocal.Error("idSmokeParticles::UpdateRenderEntity: miscounted verts");
                }

                if (tri.numVerts == 0) {

                    // they were all removed
                    renderEntity.hModel.FreeSurfaceTriangles(tri);

                    if (null == active.smokes) {
                        // remove this from the activeStages list
                        activeStages.RemoveIndex(activeStageNum);
                        activeStageNum--;
                    }
                } else {
                    // build the index list
                    int indexes = 0;
                    for (int i = 0; i < tri.numVerts; i += 4) {
                        tri.indexes[indexes + 0] = i;
                        tri.indexes[indexes + 1] = i + 2;
                        tri.indexes[indexes + 2] = i + 3;
                        tri.indexes[indexes + 3] = i;
                        tri.indexes[indexes + 4] = i + 3;
                        tri.indexes[indexes + 5] = i + 1;
                        indexes += 6;
                    }
                    tri.numIndexes = indexes;

                    modelSurface_s surf = new modelSurface_s();
                    surf.geometry = tri;
                    surf.shader = stage.material;
                    surf.id = 0;

                    renderEntity.hModel.AddSurface(surf);
                }
            }
            return true;
        }

        private static class ModelCallback extends deferredEntityCallback_t {

            public static final deferredEntityCallback_t instance = new ModelCallback();

            private ModelCallback() {
            }

            public static deferredEntityCallback_t getInstance() {
                return instance;
            }

            @Override
            public boolean run(renderEntity_s e, renderView_s v) {
                // update the particles
                if (gameLocal.smokeParticles != null) {
                    return gameLocal.smokeParticles.UpdateRenderEntity(e, v);
                }

                return true;
            }

            @Override
            public ByteBuffer AllocBuffer() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public void Read(ByteBuffer buffer) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public ByteBuffer Write() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }
        };
    };
}
