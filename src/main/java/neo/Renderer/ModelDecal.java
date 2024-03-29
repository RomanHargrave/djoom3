package neo.Renderer;

import java.util.Arrays;
import neo.Renderer.Material.decalInfo_t;
import neo.Renderer.Material.idMaterial;
import neo.Renderer.Model.idRenderModel;
import neo.Renderer.Model.modelSurface_s;
import neo.Renderer.Model.srfTriangles_s;
import static neo.Renderer.VertexCache.vertexCache;
import static neo.Renderer.tr_light.R_AddDrawSurf;
import static neo.Renderer.tr_local.tr;
import neo.Renderer.tr_local.viewEntity_s;
import static neo.Renderer.tr_main.R_AxisToModelMatrix;
import static neo.Renderer.tr_main.R_GlobalPlaneToLocal;
import static neo.Renderer.tr_main.R_GlobalPointToLocal;
import static neo.framework.Common.common;
import neo.framework.DemoFile.idDemoFile;
import neo.idlib.BV.Bounds.idBounds;
import neo.idlib.geometry.DrawVert.idDrawVert;
import neo.idlib.geometry.Winding.idFixedWinding;
import neo.idlib.geometry.Winding.idWinding;
import neo.idlib.math.Math_h.idMath;
import neo.idlib.math.Matrix.idMat3;
import static neo.idlib.math.Plane.SIDE_CROSS;
import neo.idlib.math.Plane.idPlane;
import static neo.idlib.math.Simd.SIMDProcessor;
import neo.idlib.math.Vector.idVec3;
import neo.idlib.math.Vector.idVec5;

/**
 *
 */
public class ModelDecal {

    /*
     ===============================================================================

     Decals are lightweight primitives for bullet / blood marks.
     Decals with common materials will be merged together, but additional
     decals will be allocated as needed. The material should not be
     one that receives lighting, because no interactions are generated
     for these lightweight surfaces.

     FIXME:	Decals on models in portalled off areas do not get freed
     until the area becomes visible again.

     ===============================================================================
     */
    static final int NUM_DECAL_BOUNDING_PLANES = 6;

    static class decalProjectionInfo_s {

        idVec3 projectionOrigin;
        idBounds projectionBounds;
        idPlane[] boundingPlanes = new idPlane[6];
        idPlane[] fadePlanes = new idPlane[2];
        idPlane[] textureAxis = new idPlane[2];
        idMaterial material;
        boolean parallel;
        float fadeDepth;
        int startTime;
        boolean force;
    };

    static class idRenderModelDecal {

        private static final int MAX_DECAL_VERTS = 40;
        private static final int MAX_DECAL_INDEXES = 60;
        //
        private idMaterial material;
        private srfTriangles_s tri;
        private idDrawVert[] verts = new idDrawVert[MAX_DECAL_VERTS];
        private float[] vertDepthFade = new float[MAX_DECAL_VERTS];
        private int[]/*glIndex_t*/ indexes = new int[MAX_DECAL_INDEXES];
        private int[] indexStartTime = new int[MAX_DECAL_INDEXES];
        private idRenderModelDecal nextDecal;
        //
        //

        public idRenderModelDecal() {
//	memset( &tri, 0, sizeof( tri ) );
            tri.verts = verts;
            tri.indexes = indexes;
            material = null;
            nextDecal = null;
        }
//								~idRenderModelDecal( void );
//

        public static idRenderModelDecal Alloc() {
            return new idRenderModelDecal();
        }

        public static void Free(idRenderModelDecal decal) {
//	delete decal;
        }

        // Creates decal projection info.
        public static boolean CreateProjectionInfo(decalProjectionInfo_s info, final idFixedWinding winding, final idVec3 projectionOrigin, final boolean parallel, final float fadeDepth, final idMaterial material, final int startTime) {

            if (winding.GetNumPoints() != NUM_DECAL_BOUNDING_PLANES - 2) {
                common.Printf("idRenderModelDecal::CreateProjectionInfo: winding must have %d points\n", NUM_DECAL_BOUNDING_PLANES - 2);
                return false;
            }

            assert (material != null);

            info.projectionOrigin = projectionOrigin;
            info.material = material;
            info.parallel = parallel;
            info.fadeDepth = fadeDepth;
            info.startTime = startTime;
            info.force = false;

            // get the winding plane and the depth of the projection volume
            idPlane windingPlane = new idPlane();
            winding.GetPlane(windingPlane);
            float depth = windingPlane.Distance(projectionOrigin);

            // find the bounds for the projection
            winding.GetBounds(info.projectionBounds);
            if (parallel) {
                info.projectionBounds.ExpandSelf(depth);
            } else {
                info.projectionBounds.AddPoint(projectionOrigin);
            }

            // calculate the world space projection volume bounding planes, positive sides face outside the decal
            if (parallel) {
                for (int i = 0; i < winding.GetNumPoints(); i++) {
                    idVec3 edge = winding.oGet((i + 1) % winding.GetNumPoints()).ToVec3().oMinus(winding.oGet(i).ToVec3());
                    info.boundingPlanes[i].Normal().Cross(windingPlane.Normal(), edge);
                    info.boundingPlanes[i].Normalize();
                    info.boundingPlanes[i].FitThroughPoint(winding.oGet(i).ToVec3());
                }
            } else {
                for (int i = 0; i < winding.GetNumPoints(); i++) {
                    info.boundingPlanes[i].FromPoints(projectionOrigin, winding.oGet(i).ToVec3(), winding.oGet((i + 1) % winding.GetNumPoints()).ToVec3());
                }
            }
            info.boundingPlanes[NUM_DECAL_BOUNDING_PLANES - 2] = windingPlane;
            info.boundingPlanes[NUM_DECAL_BOUNDING_PLANES - 2].oMinSet(3, depth);
            info.boundingPlanes[NUM_DECAL_BOUNDING_PLANES - 1] = windingPlane.oNegative();

            // fades will be from these plane
            info.fadePlanes[0] = windingPlane;
            info.fadePlanes[0].oMinSet(3, fadeDepth);
            info.fadePlanes[1] = windingPlane.oNegative();
            info.fadePlanes[1].oPluSet(3, depth - fadeDepth);

            // calculate the texture vectors for the winding
            float len, texArea, inva;
            idVec3 temp = new idVec3();
            idVec5 d0 = new idVec5(), d1 = new idVec5();

            final idVec5 a = winding.oGet(0);
            final idVec5 b = winding.oGet(1);
            final idVec5 c = winding.oGet(2);

            d0.oSet(b.ToVec3().oMinus(a.ToVec3()));
            d0.s = b.s - a.s;
            d0.t = b.t - a.t;
            d1.oSet(c.ToVec3().oMinus(a.ToVec3()));
            d1.s = c.s - a.s;
            d1.t = c.t - a.t;

            texArea = (d0.oGet(3) * d1.oGet(4)) - (d0.oGet(4) * d1.oGet(3));
            inva = 1.0f / texArea;

            temp.oSet(0, (d0.oGet(0) * d1.oGet(4) - d0.oGet(4) * d1.oGet(0)) * inva);
            temp.oSet(1, (d0.oGet(1) * d1.oGet(4) - d0.oGet(4) * d1.oGet(1)) * inva);
            temp.oSet(2, (d0.oGet(2) * d1.oGet(4) - d0.oGet(4) * d1.oGet(2)) * inva);
            len = temp.Normalize();
            info.textureAxis[0].SetNormal(temp.oMultiply(1.0f / len));
            info.textureAxis[0].oSet(3, winding.oGet(0).s - (winding.oGet(0).ToVec3().oMultiply(info.textureAxis[0].Normal())));

            temp.oSet(0, (d0.oGet(3) * d1.oGet(0) - d0.oGet(0) * d1.oGet(3)) * inva);
            temp.oSet(1, (d0.oGet(3) * d1.oGet(1) - d0.oGet(1) * d1.oGet(3)) * inva);
            temp.oSet(2, (d0.oGet(3) * d1.oGet(2) - d0.oGet(2) * d1.oGet(3)) * inva);
            len = temp.Normalize();
            info.textureAxis[1].SetNormal(temp.oMultiply(1.0f / len));
            info.textureAxis[1].oSet(3, winding.oGet(0).s - (winding.oGet(0).ToVec3().oMultiply(info.textureAxis[1].Normal())));

            return true;
        }

        // Transform the projection info from global space to local.
        public static void GlobalProjectionInfoToLocal(decalProjectionInfo_s localInfo, final decalProjectionInfo_s info, final idVec3 origin, final idMat3 axis) {
            float[] modelMatrix = new float[16];

            R_AxisToModelMatrix(axis, origin, modelMatrix);

            for (int j = 0; j < NUM_DECAL_BOUNDING_PLANES; j++) {
                R_GlobalPlaneToLocal(modelMatrix, info.boundingPlanes[j], localInfo.boundingPlanes[j]);
            }
            R_GlobalPlaneToLocal(modelMatrix, info.fadePlanes[0], localInfo.fadePlanes[0]);
            R_GlobalPlaneToLocal(modelMatrix, info.fadePlanes[1], localInfo.fadePlanes[1]);
            R_GlobalPlaneToLocal(modelMatrix, info.textureAxis[0], localInfo.textureAxis[0]);
            R_GlobalPlaneToLocal(modelMatrix, info.textureAxis[1], localInfo.textureAxis[1]);
            R_GlobalPointToLocal(modelMatrix, info.projectionOrigin, localInfo.projectionOrigin);
            localInfo.projectionBounds = info.projectionBounds;
            localInfo.projectionBounds.TranslateSelf(origin.oNegative());
            localInfo.projectionBounds.RotateSelf(axis.Transpose());
            localInfo.material = info.material;
            localInfo.parallel = info.parallel;
            localInfo.fadeDepth = info.fadeDepth;
            localInfo.startTime = info.startTime;
            localInfo.force = info.force;
        }

        // Creates a deal on the given model.
        public void CreateDecal(final idRenderModel model, final decalProjectionInfo_s localInfo) {

            // check all model surfaces
            for (int surfNum = 0; surfNum < model.NumSurfaces(); surfNum++) {
                final modelSurface_s surf = model.Surface(surfNum);

                // if no geometry or no shader
                if (null == surf.geometry || null == surf.shader) {
                    continue;
                }

                // decals and overlays use the same rules
                if (!localInfo.force && !surf.shader.AllowOverlays()) {
                    continue;
                }

                srfTriangles_s stri = surf.geometry;

                // if the triangle bounds do not overlap with projection bounds
                if (!localInfo.projectionBounds.IntersectsBounds(stri.bounds)) {
                    continue;
                }

                // allocate memory for the cull bits
                byte[] cullBits = new byte[stri.numVerts];

                // catagorize all points by the planes
                SIMDProcessor.DecalPointCull(cullBits, localInfo.boundingPlanes, stri.verts, stri.numVerts);

                // find triangles inside the projection volume
                for (int triNum = 0, index = 0; index < stri.numIndexes; index += 3, triNum++) {
                    int v1 = stri.indexes[index + 0];
                    int v2 = stri.indexes[index + 1];
                    int v3 = stri.indexes[index + 2];

                    // skip triangles completely off one side
                    if ((cullBits[v1] & cullBits[v2] & cullBits[v3]) != 0) {
                        continue;
                    }

                    // skip back facing triangles
                    if (stri.facePlanes != null && stri.facePlanesCalculated
                            && stri.facePlanes[triNum].Normal().oMultiply(localInfo.boundingPlanes[NUM_DECAL_BOUNDING_PLANES - 2].Normal()) < -0.1f) {
                        continue;
                    }

                    // create a winding with texture coordinates for the triangle
                    idFixedWinding fw = new idFixedWinding();
                    fw.SetNumPoints(3);
                    if (localInfo.parallel) {
                        for (int j = 0; j < 3; j++) {
                            fw.oGet(j).oSet(stri.verts[stri.indexes[index + j]].xyz);
                            fw.oGet(j).s = localInfo.textureAxis[0].Distance(fw.oGet(j).ToVec3());
                            fw.oGet(j).t = localInfo.textureAxis[1].Distance(fw.oGet(j).ToVec3());
                        }
                    } else {
                        for (int j = 0; j < 3; j++) {
                            idVec3 dir;
                            float[] scale = new float[1];

                            fw.oGet(j).oSet(stri.verts[stri.indexes[index + j]].xyz);
                            dir = fw.oGet(j).ToVec3().oMinus(localInfo.projectionOrigin);
                            localInfo.boundingPlanes[NUM_DECAL_BOUNDING_PLANES - 1].RayIntersection(fw.oGet(j).ToVec3(), dir, scale);
                            dir = fw.oGet(j).ToVec3().oPlus(dir.oMultiply(scale[0]));
                            fw.oGet(j).s = localInfo.textureAxis[0].Distance(dir);
                            fw.oGet(j).t = localInfo.textureAxis[1].Distance(dir);
                        }
                    }

                    int orBits = cullBits[v1] | cullBits[v2] | cullBits[v3];

                    // clip the exact surface triangle to the projection volume
                    for (int j = 0; j < NUM_DECAL_BOUNDING_PLANES; j++) {
                        if ((orBits & (1 << j)) != 0) {
                            if (!fw.ClipInPlace(localInfo.boundingPlanes[j].oNegative())) {
                                break;
                            }
                        }
                    }

                    if (fw.GetNumPoints() == 0) {
                        continue;
                    }

                    AddDepthFadedWinding(fw, localInfo.material, localInfo.fadePlanes, localInfo.fadeDepth, localInfo.startTime);
                }
            }
        }

        // Remove decals that are completely faded away.
        public static idRenderModelDecal RemoveFadedDecals(idRenderModelDecal decals, int time) {
            int i, j, minTime, newNumIndexes, newNumVerts;
            int[] inUse = new int[MAX_DECAL_VERTS];
            decalInfo_t decalInfo;
            idRenderModelDecal nextDecal;

            if (decals == null) {
                return null;
            }

            // recursively free any next decals
            decals.nextDecal = RemoveFadedDecals(decals.nextDecal, time);

            // free the decals if no material set
            if (decals.material == null) {
                nextDecal = decals.nextDecal;
                Free(decals);
                return nextDecal;
            }

            decalInfo = decals.material.GetDecalInfo();
            minTime = time - (decalInfo.stayTime + decalInfo.fadeTime);

            newNumIndexes = 0;
            for (i = 0; i < decals.tri.numIndexes; i += 3) {
                if (decals.indexStartTime[i] > minTime) {
                    // keep this triangle
                    if (newNumIndexes != i) {
                        for (j = 0; j < 3; j++) {
                            decals.tri.indexes[newNumIndexes + j] = decals.tri.indexes[i + j];
                            decals.indexStartTime[newNumIndexes + j] = decals.indexStartTime[i + j];
                        }
                    }
                    newNumIndexes += 3;
                }
            }

            // free the decals if all trianges faded away
            if (newNumIndexes == 0) {
                nextDecal = decals.nextDecal;
                Free(decals);
                return nextDecal;
            }

            decals.tri.numIndexes = newNumIndexes;

//	memset( inUse, 0, sizeof( inUse ) );
            Arrays.fill(inUse, 0);
            for (i = 0; i < decals.tri.numIndexes; i++) {
                inUse[decals.tri.indexes[i]] = 1;
            }

            newNumVerts = 0;
            for (i = 0; i < decals.tri.numVerts; i++) {
                if (0 == inUse[i]) {
                    continue;
                }
                decals.tri.verts[newNumVerts] = decals.tri.verts[i];
                decals.vertDepthFade[newNumVerts] = decals.vertDepthFade[i];
                inUse[i] = newNumVerts;
                newNumVerts++;
            }
            decals.tri.numVerts = newNumVerts;

            for (i = 0; i < decals.tri.numIndexes; i++) {
                decals.tri.indexes[i] = inUse[decals.tri.indexes[i]];
            }

            return decals;
        }

        // Updates the vertex colors, removing any faded indexes,
        // then copy the verts to temporary vertex cache and adds a drawSurf.
        public void AddDecalDrawSurf(viewEntity_s space) {
            int i, j, maxTime;
            float f;
            decalInfo_t decalInfo;

            if (tri.numIndexes == 0) {
                return;
            }

            // fade down all the verts with time
            decalInfo = material.GetDecalInfo();
            maxTime = decalInfo.stayTime + decalInfo.fadeTime;

            // set vertex colors and remove faded triangles
            for (i = 0; i < tri.numIndexes; i += 3) {
                int deltaTime = tr.viewDef.renderView.time - indexStartTime[i];

                if (deltaTime > maxTime) {
                    continue;
                }

                if (deltaTime <= decalInfo.stayTime) {
                    continue;
                }

                deltaTime -= decalInfo.stayTime;
                f = (float) deltaTime / decalInfo.fadeTime;

                for (j = 0; j < 3; j++) {
                    int ind = tri.indexes[i + j];

                    for (int k = 0; k < 4; k++) {
                        float fcolor = decalInfo.start[k] + (decalInfo.end[k] - decalInfo.start[k]) * f;
                        int icolor = idMath.FtoiFast(fcolor * vertDepthFade[ind] * 255.0f);
                        if (icolor < 0) {
                            icolor = 0;
                        } else if (icolor > 255) {
                            icolor = 255;
                        }
                        tri.verts[ind].color[k] = (short) icolor;
                    }
                }
            }

            // copy the tri and indexes to temp heap memory,
            // because if we are running multi-threaded, we wouldn't
            // be able to reorganize the index list
            srfTriangles_s newTri;//(srfTriangles_s) R_FrameAlloc(sizeof(newTri));
            newTri = tri;

            // copy the current vertexes to temp vertex cache
            newTri.ambientCache = vertexCache.AllocFrameTemp(tri.verts, tri.numVerts * idDrawVert.SIZE_B);

            // create the drawsurf
            R_AddDrawSurf(newTri, space, space.entityDef.parms, material, space.scissorRect);
        }

        // Returns the next decal in the chain.
        public idRenderModelDecal Next() {
            return nextDecal;
        }

        public void ReadFromDemoFile(idDemoFile f) {
            // FIXME: implement
        }

        public void WriteToDemoFile(idDemoFile f) {
            // FIXME: implement
        }
        // Adds the winding triangles to the appropriate decal in the
        // chain, creating a new one if necessary.

        private void AddWinding(final idWinding w, final idMaterial decalMaterial, final idPlane[] fadePlanes/*[2]*/, float fadeDepth, int startTime) {
            int i;
            float invFadeDepth, fade;
            decalInfo_t decalInfo;

            if ((material == null || material == decalMaterial)
                    && tri.numVerts + w.GetNumPoints() < MAX_DECAL_VERTS
                    && tri.numIndexes + (w.GetNumPoints() - 2) * 3 < MAX_DECAL_INDEXES) {

                material = decalMaterial;

                // add to this decal
                decalInfo = material.GetDecalInfo();
                invFadeDepth = -1.0f / fadeDepth;

                for (i = 0; i < w.GetNumPoints(); i++) {
                    fade = fadePlanes[0].Distance(w.oGet(i).ToVec3()) * invFadeDepth;
                    if (fade < 0.0f) {
                        fade = fadePlanes[1].Distance(w.oGet(i).ToVec3()) * invFadeDepth;
                    }
                    if (fade < 0.0f) {
                        fade = 0.0f;
                    } else if (fade > 0.99f) {
                        fade = 1.0f;
                    }
                    fade = 1.0f - fade;
                    vertDepthFade[tri.numVerts + i] = fade;
                    tri.verts[tri.numVerts + i].xyz = w.oGet(i).ToVec3();
                    tri.verts[tri.numVerts + i].st.oSet(0, w.oGet(i).s);
                    tri.verts[tri.numVerts + i].st.oSet(1, w.oGet(i).t);
                    for (int k = 0; k < 4; k++) {
                        int icolor = idMath.FtoiFast(decalInfo.start[k] * fade * 255.0f);
                        if (icolor < 0) {
                            icolor = 0;
                        } else if (icolor > 255) {
                            icolor = 255;
                        }
                        tri.verts[tri.numVerts + i].color[k] = (short) icolor;
                    }
                }
                for (i = 2; i < w.GetNumPoints(); i++) {
                    tri.indexes[tri.numIndexes + 0] = tri.numVerts;
                    tri.indexes[tri.numIndexes + 1] = tri.numVerts + i - 1;
                    tri.indexes[tri.numIndexes + 2] = tri.numVerts + i;
                    indexStartTime[tri.numIndexes]
                            = indexStartTime[tri.numIndexes + 1]
                            = indexStartTime[tri.numIndexes + 2] = startTime;
                    tri.numIndexes += 3;
                }
                tri.numVerts += w.GetNumPoints();
                return;
            }

            // if we are at the end of the list, create a new decal
            if (null == nextDecal) {
                nextDecal = idRenderModelDecal.Alloc();
            }
            // let the next decal on the chain take a look
            nextDecal.AddWinding(w, decalMaterial, fadePlanes, fadeDepth, startTime);
        }

        // Adds depth faded triangles for the winding to the appropriate
        // decal in the chain, creating a new one if necessary.
        // The part of the winding at the front side of both fade planes is not faded.
        // The parts at the back sides of the fade planes are faded with the given depth.
        private void AddDepthFadedWinding(final idWinding w, final idMaterial decalMaterial, final idPlane[] fadePlanes/*[2]*/, float fadeDepth, int startTime) {
            idFixedWinding front, back;

            front = (idFixedWinding) w;
            back = new idFixedWinding();
            if (front.Split(back, fadePlanes[0], 0.1f) == SIDE_CROSS) {
                AddWinding(back, decalMaterial, fadePlanes, fadeDepth, startTime);
            }

            if (front.Split(back, fadePlanes[1], 0.1f) == SIDE_CROSS) {
                AddWinding(back, decalMaterial, fadePlanes, fadeDepth, startTime);
            }

            AddWinding(front, decalMaterial, fadePlanes, fadeDepth, startTime);
        }
    };
}
