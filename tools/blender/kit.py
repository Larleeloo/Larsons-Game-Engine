"""
The bits every model-building script in this folder needs, in one place.

`ranger.py` is deliberately **not** built on this. It is the script that made
`characters/walker.glb`, the figure this game has shipped since people had
bodies at all, and a refactor that changed one rounding in it would change
that figure — silently, in a binary that does not diff. It keeps its own
copies. Everything written after it lives here:

    figures.py     the two players' measurements, and their palettes
    bodies.py      both player figures, as bodies rather than outfits
    cosmetics.py   the wardrobe, fitted to each of them

What is in here is the vocabulary those three are written in, and it is the
same vocabulary `ranger.py` invented:

* a **Part** is a heap of world-space quads with a material name on each of
  them. One Part per bone, welded into one mesh at the end, so a bone is a
  vertex group and there is no weight painting anywhere in this folder;
* **colour is a material**, never a texture, and the hex you type is the hex
  the game draws — see `material`;
* **rigid parts, overlapping at the joints**, because the importer gives each
  triangle to the one bone with the most weight across its corners and moves
  it rigidly. There is nothing here a gradient could do.

The contract all of it is written against is
`src/main/resources/watch/models/README.md`; where this file and that one
disagree, that one is right.

--- axes ------------------------------------------------------------------

Blender's coordinates are the game's coordinates. A point modelled at
`(x, y, z)` arrives at `(x, y, z)` on a figure standing at the origin.

    +Z  up
    -Y  the way the figure faces and walks   (Blender's Front view, numpad 1)
    +X  the side the `_l` bones go on
"""

import math

import bpy
import bmesh
from mathutils import Matrix, Quaternion, Vector

#: Frames a second every clip in this folder is written at.
FPS = 24

#: The order `Part.box` writes its faces in, so a caller can name one.
FACES = ("-z", "+z", "-y", "+y", "+x", "-x")

#: How far inside a solid a face has to be before `Part.bury` drops it.
BURIED = 1e-6


# --- colour ----------------------------------------------------------------

def linear(channel):
    """One sRGB byte as the linear value Blender's colour picker stores."""
    c = channel / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def shade(rgb, scale):
    """`ShopModel.shade`: a scale in sRGB space, clamped, byte by byte."""
    r = min(255, int(((rgb >> 16) & 0xFF) * scale))
    g = min(255, int(((rgb >> 8) & 0xFF) * scale))
    b = min(255, int((rgb & 0xFF) * scale))
    return (r << 16) | (g << 8) | b


def material(name, rgb):
    """A flat colour, as a Principled BSDF with nothing plugged into it.

    Base Color is set in linear because that is what Blender stores and what
    glTF writes; the importer converts back, so the byte in `rgb` is the byte
    the game draws. The node is found by *type* rather than by name, which is
    localised and has been renamed between Blender versions.
    """
    mat = bpy.data.materials.get(name)
    if mat is None:
        mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    value = (linear((rgb >> 16) & 0xFF), linear((rgb >> 8) & 0xFF),
             linear(rgb & 0xFF))
    for node in mat.node_tree.nodes:
        if node.type == "BSDF_PRINCIPLED":
            node.inputs["Base Color"].default_value = value + (1.0,)
            if "Roughness" in node.inputs:
                node.inputs["Roughness"].default_value = 1.0
            if "Metallic" in node.inputs:
                node.inputs["Metallic"].default_value = 0.0
            break
    mat.diffuse_color = value + (1.0,)
    return mat


def palette(colours, derived=None):
    """A whole tin of them: {name: rgb}, plus {name: (source, scale)}."""
    made = {name: material(name, rgb) for name, rgb in colours.items()}
    for name, (source, scale) in (derived or {}).items():
        made[name] = material(name, shade(colours[source], scale))
    return made


# --- geometry --------------------------------------------------------------

class Part:
    """Quads and their materials, in world coordinates.

    Everything in this folder is one of these per bone. They carry world
    coordinates rather than local ones because every number in `figures.py`
    is a landmark measured from the floor, and converting each of them into
    some bone's frame on the way in would be one more place to get a sign
    wrong.
    """

    def __init__(self):
        self.verts = []
        self.faces = []
        self.mats = []
        self.solids = []

    # --- adding to it ------------------------------------------------------

    def add(self, verts, faces, mats):
        base = len(self.verts)
        self.verts.extend(verts)
        self.faces.extend(tuple(base + i for i in face) for face in faces)
        self.mats.extend(mats)

    def box(self, centre, size, material, faces=None, rotation=None):
        """A box, by its centre and its *full* size — width, depth, height.

        Full sizes rather than half-extents because every table in this
        folder is written in full sizes, and converting them here would only
        be a chance to halve one of them twice.

        `faces` overrides the material on named faces: `{"-z": "shadow"}`
        paints the underside alone, which is how one box carries two colours
        without costing a second box.
        """
        cx, cy, cz = centre
        hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
        corners = [Vector((sx * hx, sy * hy, sz * hz))
                   for sx, sy, sz in ((-1, -1, -1), (1, -1, -1), (1, 1, -1),
                                      (-1, 1, -1), (-1, -1, 1), (1, -1, 1),
                                      (1, 1, 1), (-1, 1, 1))]
        if rotation is not None:
            corners = [rotation @ v for v in corners]
        verts = [(cx + v.x, cy + v.y, cz + v.z) for v in corners]
        quads = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4),
                 (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)]
        paint = [(faces or {}).get(side, material) for side in FACES]
        self.add(verts, quads, paint)
        if rotation is None:
            # An axis-aligned box can seal another box's faces. A turned one
            # is skipped rather than approximated: its bounding box is not
            # itself, and a face culled against the corner of a box that is
            # not there is a hole in the model.
            self.solids.append(((cx - hx, cy - hy, cz - hz),
                                (cx + hx, cy + hy, cz + hz)))

    def strut(self, start, end, width, depth, material):
        """A box laid between two points — a strap, a wire, a feather quill.

        Built axis-aligned and then turned onto the line, so the object it
        lands in still has an identity transform and `Ctrl+A` has nothing
        left to do.
        """
        start, end = Vector(start), Vector(end)
        along = end - start
        if along.length < 1e-9:
            return
        turn = Vector((0, 0, 1)).rotation_difference(along.normalized())
        self.box((start + end) / 2, (width, depth, along.length), material,
                 rotation=turn.to_matrix())

    def plate(self, centre, size, material, tilt=0.0, turn=0.0, faces=None):
        """A box tipped about X by `tilt` and about Z by `turn`, in radians.

        **The one shape that buys most of the detail in the wardrobe.** A
        brim that slopes, a cape panel that stands off the shoulders and
        falls in toward the knee, a gaiter that tapers up the shin: all of
        them are a box that is not quite square to the world, and a piece
        built only out of ones that are reads as a stack of crates.
        """
        rotation = (Matrix.Rotation(turn, 3, "Z")
                    @ Matrix.Rotation(tilt, 3, "X"))
        self.box(centre, size, material, faces=faces, rotation=rotation)

    def roll(self, start, end, radius, material, sides=8, end_material=None):
        """A drum laid between two points — a bedroll, a quill, an antler
        beam, a rope.

        `prism` turned onto a line, the way `strut` is a box turned onto one,
        and here for the same reason: everything cylindrical in this world
        lies along something rather than standing on end.
        """
        start, end_point = Vector(start), Vector(end)
        along = end_point - start
        if along.length < 1e-9:
            return
        turn = Vector((0, 0, 1)).rotation_difference(along.normalized())
        self.prism((0, 0, 0), radius, along.length, material, sides=sides,
                   top_material=end_material, bottom_material=end_material,
                   place=(turn.to_matrix(), (start + end_point) / 2))

    def prism(self, centre, radius, height, material, sides=8, squash=1.0,
              top_radius=None, top_material=None, bottom_material=None,
              turn=0.0, place=None):
        """A solid `sides`-gon drum — a hat brim, a crown, a lens, a bead.

        **This is what a round thing is in this game**, and it is the cheapest
        detail in the whole folder. Everything is flat-shaded, so an octagon
        reads as a circle from any distance you can see a person at, and it
        costs 28 triangles where eight little boxes round the same circle
        cost 96 and a subdivided cylinder costs three hundred.

        `top_radius` tapers it — a bell crown, a tapered ferrule — and
        `squash` narrows it fore-and-aft, because a head is not a circle and
        a hat that fits one is not either. `place` is `(matrix, origin)`, and
        is how `roll` lays one on its side.
        """
        top_radius = radius if top_radius is None else top_radius
        cx, cy, cz = centre
        low, high = cz - height / 2, cz + height / 2
        rim = []
        for level, r in ((low, radius), (high, top_radius)):
            ring = []
            for i in range(sides):
                angle = turn + math.tau * (i + 0.5) / sides
                ring.append(Vector((math.sin(angle) * r,
                                    math.cos(angle) * r * squash, level)))
            rim.append(ring)
        points = rim[0] + rim[1]
        if place is not None:
            matrix, origin = place
            points = [Vector(origin) + matrix @ p for p in points]
        else:
            points = [Vector((cx, cy, 0)) + p for p in points]
        verts = [tuple(p) for p in points]
        faces = [tuple(range(sides - 1, -1, -1)),
                 tuple(range(sides, sides * 2))]
        for i in range(sides):
            j = (i + 1) % sides
            faces.append((i, j, sides + j, sides + i))
        paint = ([bottom_material or material, top_material or material]
                 + [material] * sides)
        self.add(verts, faces, paint)

    def ring(self, centre, radius, thickness, height, material, sides=8,
             squash=1.0, faces=None):
        """A *hollow* band of `sides` panels round a vertical axis — a
        circlet, a lens rim, a barrel hoop.

        Where `prism` is a filled drum this is the band alone, for the few
        things you have to be able to see through. It costs a box a panel, so
        six is the fewest that still reads as round and eight is the most
        anything here can afford.
        """
        for i in range(sides):
            angle = math.tau * (i + 0.5) / sides
            x = math.sin(angle) * radius
            y = math.cos(angle) * radius * squash
            # Each panel is a plank on the tangent, wide enough to meet its
            # neighbours at the corners: a chord of the circle, not an arc.
            width = 2 * radius * math.tan(math.pi / sides)
            self.plate((centre[0] + x, centre[1] + y, centre[2]),
                       (width, thickness, height), material,
                       turn=-angle, faces=faces)

    def pyramid(self, centre_xy, base_z, base_size, apex_z, material):
        """Four sides to a point: the crown of a campaign hat."""
        cx, cy = centre_xy
        h = base_size / 2
        verts = [(cx - h, cy - h, base_z), (cx + h, cy - h, base_z),
                 (cx + h, cy + h, base_z), (cx - h, cy + h, base_z),
                 (cx, cy, apex_z)]
        faces = [(0, 3, 2, 1), (0, 1, 4), (1, 2, 4), (2, 3, 4), (3, 0, 4)]
        self.add(verts, faces, [material] * 5)

    def taper(self, bottom, top, bottom_size, top_size, material):
        """A box with a different width at each end — a sleeve, a boot cuff,
        a cloak panel that hangs wider at the hem than at the yoke.

        Six faces, like a box, and the one thing a box cannot do: stop being
        the same size all the way up. A skirt built out of stacked boxes has
        a staircase down its side and this does not.
        """
        (bx, by, bz), (tx, ty, tz) = bottom, top
        bw, bd = bottom_size[0] / 2, bottom_size[1] / 2
        tw, td = top_size[0] / 2, top_size[1] / 2
        verts = [(bx - bw, by - bd, bz), (bx + bw, by - bd, bz),
                 (bx + bw, by + bd, bz), (bx - bw, by + bd, bz),
                 (tx - tw, ty - td, tz), (tx + tw, ty - td, tz),
                 (tx + tw, ty + td, tz), (tx - tw, ty + td, tz)]
        quads = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4),
                 (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)]
        self.add(verts, quads, [material] * 6)

    # --- taking away from it ----------------------------------------------

    def bury(self):
        """Drop the faces sealed inside another box of this same piece.

        **This is where the budget for the detail comes from.** A figure is
        built out of overlapping boxes — that is what stops daylight showing
        at the joints — and an overlap means faces inside solid wood: the top
        of a neck inside a skull, the base of a hat's peak inside its crown.
        Nobody will ever see one, and every one of them costs two triangles
        the software rasteriser has to walk.

        Only boxes in the *same* piece may seal a face, because a piece is
        the unit that moves rigidly. A face hidden inside a collar is not
        hidden at all once the head turns and takes the collar's neighbour
        with it.

        Strictly inside, so a face lying flush on another box's surface
        survives: those are visible about half the time, and telling which
        half is a z-fighting argument rather than an arithmetic one.
        """
        faces, mats, used = [], [], set()
        for face, mat in zip(self.faces, self.mats):
            points = [self.verts[i] for i in face]
            if any(all(lo[a] + BURIED <= p[a] <= hi[a] - BURIED
                       for p in points for a in (0, 1, 2))
                   for lo, hi in self.solids):
                continue
            faces.append(face)
            mats.append(mat)
            used.update(face)
        # Re-index, or the orphaned corners ship as loose vertices.
        order = sorted(used)
        at = {v: i for i, v in enumerate(order)}
        self.verts = [self.verts[v] for v in order]
        self.faces = [tuple(at[i] for i in face) for face in faces]
        self.mats = mats
        return self

    def empty(self):
        return not self.faces


def mirrored(part, spec):
    """Emit a spec at +X and again at -X.

    Everything two-of is written once, on the +X side — which is the `_l`
    side, and the side every table in this folder quotes its numbers from.
    """
    for side in (1, -1):
        kind = spec[0]
        if kind == "box":
            _, centre, size, mat = spec[:4]
            faces = spec[4] if len(spec) > 4 else None
            part.box((side * centre[0], centre[1], centre[2]), size, mat,
                     faces=faces)
        elif kind == "strut":
            _, start, end, width, depth, mat = spec
            part.strut((side * start[0], start[1], start[2]),
                       (side * end[0], end[1], end[2]), width, depth, mat)
        elif kind == "plate":
            _, centre, size, mat, tilt, turn = spec
            part.plate((side * centre[0], centre[1], centre[2]), size, mat,
                       tilt=tilt, turn=side * turn)


# --- putting it in the scene -----------------------------------------------

def to_object_mode():
    obj = bpy.context.view_layer.objects.active
    if obj is not None and obj.mode != "OBJECT":
        bpy.ops.object.mode_set(mode="OBJECT")


def fresh_collection(name):
    """An empty collection under that name, and the last one of it gone."""
    old = bpy.data.collections.get(name)
    if old:
        for obj in list(old.objects):
            bpy.data.objects.remove(obj, do_unlink=True)
        bpy.data.collections.remove(old)
    made = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(made)
    return made


def mesh_object(collection, name, part, tin):
    """One bone's worth of boxes, welded into one triangulated mesh.

    Triangulated here rather than left to a modifier so the file is
    triangulated however it is exported: a quad is otherwise fanned about its
    first corner, which is right for a convex face and wrong for the rest.
    """
    used = []
    for mat in part.mats:
        if mat not in used:
            used.append(mat)
    slot = {name: i for i, name in enumerate(used)}

    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(part.verts, [], part.faces)
    mesh.update()
    for mat in used:
        mesh.materials.append(tin[mat])
    for polygon, mat in zip(mesh.polygons, part.mats):
        polygon.material_index = slot[mat]

    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    bm.to_mesh(mesh)
    bm.free()
    mesh.update()
    # Flat everywhere. A subdivided sphere costs three hundred triangles to
    # look exactly like a faceted one from six metres away.
    for polygon in mesh.polygons:
        polygon.use_smooth = False

    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)
    return obj


def build_rig(collection, name, bones):
    """An armature from a list of `(name, head, tail, parent)`.

    The names are the entire binding contract — README §10. A bone that
    matches nothing inherits its parent's joint, and a root that matches
    nothing is the body, so the cost of getting one wrong is a piece that
    does not move rather than a piece that vanishes.
    """
    armature = bpy.data.armatures.new(name)
    rig = bpy.data.objects.new(name, armature)
    collection.objects.link(rig)

    for other in bpy.context.view_layer.objects:
        other.select_set(False)
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    try:
        made = {}
        for bone_name, head, tail, parent in bones:
            bone = armature.edit_bones.new(bone_name)
            bone.head = Vector(head)
            bone.tail = Vector(tail)
            bone.use_connect = False
            if parent:
                bone.parent = made[parent]
            made[bone_name] = bone
    finally:
        bpy.ops.object.mode_set(mode="OBJECT")
    rig.show_in_front = True
    return rig


def bind(obj, rig, bone):
    """Every vertex to one bone, at weight one.

    Which is all the rigging this renderer can use: it takes the dominant
    bone per triangle and moves it rigidly, so a gradient across a joint
    would be thrown away. The pieces overlap at the joints instead, which is
    what stops daylight showing through when a limb swings.
    """
    group = obj.vertex_groups.new(name=bone)
    group.add(range(len(obj.data.vertices)), 1.0, "REPLACE")
    modifier = obj.modifiers.new("Armature", "ARMATURE")
    modifier.object = rig
    obj.parent = rig
    obj.matrix_parent_inverse = rig.matrix_world.inverted()


# --- animation -------------------------------------------------------------
#
# Written in *world* axes and converted into each bone's own space on the way
# in, so that "swing the leg forward" is a number about global X here and not
# a guess about which way a bone's roll happens to point.
#
#   about +X, negative  ->  swings a limb forward, the way the figure faces
#   about +Y, positive  ->  swings a limb toward -X
#   about +Z, positive  ->  turn toward +X
#
# **The first two are written for a limb, and a limb hangs below its pivot.**
# Anything standing *above* its pivot — a chest on a waist, a head on a neck —
# goes the other way, because the sign follows which side of the joint the
# geometry is on and not which bone it is.

def local_axis(rig, bone, axis):
    """A world axis, in the bone's own rest space."""
    rest = rig.data.bones[bone].matrix_local.to_3x3()
    return (rest.inverted() @ Vector(axis)).normalized()


def pose(rig, bone, frame, pitch=0.0, roll=0.0, yaw=0.0, shift=None):
    """One bone at one frame: three world-axis turns and an offset."""
    if bone not in rig.pose.bones:
        return
    pb = rig.pose.bones[bone]
    pb.rotation_mode = "QUATERNION"
    turn = Quaternion((1, 0, 0), 0)
    for axis, angle in (((1, 0, 0), pitch), ((0, 1, 0), roll), ((0, 0, 1), yaw)):
        if angle:
            turn = turn @ Quaternion(local_axis(rig, bone, axis), angle)
    pb.rotation_quaternion = turn
    pb.keyframe_insert("rotation_quaternion", frame=frame)
    if shift is not None:
        rest = rig.data.bones[bone].matrix_local.to_3x3()
        pb.location = rest.inverted() @ Vector(shift)
        pb.keyframe_insert("location", frame=frame)


def action(rig, name):
    """A named, empty action — and the last one of that name, gone.

    Running a script twice would otherwise leave `idle.001` beside `idle`,
    both with fake users, and both would be exported: the importer reads a
    clip's name as a `_`-separated prefix, so `idle_001` claims IDLE as
    loudly as `idle` does and which one wins is whichever it saw last.
    """
    for old in list(bpy.data.actions):
        if old.name.split(".")[0] == name:
            old.use_fake_user = False
            bpy.data.actions.remove(old)
    made = bpy.data.actions.new(name)
    made.use_fake_user = True
    if rig.animation_data is None:
        rig.animation_data_create()
    rig.animation_data.action = made
    return made


def rest(rig):
    for pb in rig.pose.bones:
        pb.rotation_mode = "QUATERNION"
        pb.rotation_quaternion = Quaternion((1, 0, 0), 0)
        pb.location = Vector((0, 0, 0))


# --- export ----------------------------------------------------------------

def export(path, objects):
    """Write `objects` to a `.glb` at `path`, with the settings §15 asks for.

    Selection-limited rather than whole-scene, because `cosmetics.py` builds
    the wardrobe once and writes thirty-six files out of it: exporting the
    scene would put every hat inside every coat.
    """
    import os

    to_object_mode()
    for obj in bpy.context.view_layer.objects:
        obj.select_set(False)
    for obj in objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objects[0] if objects else None

    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format="GLB",
        use_selection=True,
        export_yup=True,                 # README §9: +Y up is the default
        export_apply=True,               # modifiers, of which there are none
        export_materials="EXPORT",
        export_animations=True,
        export_animation_mode="ACTIONS",
        export_bake_animation=True,      # no IK, no constraints, no drivers
        export_texcoords=False,          # textures are not read at all
        export_normals=True,
        export_skins=True,
    )
    return path
