# SurroundRoom

SurroundRoom is a plug-in for [Sweet Home 3D](https://www.sweethome3d.com) that surrounds floors and walls with copies of a piece of furniture: hedges around a garden, chairs along a wall, a fence around a terrace, and so on.

![SurroundRoom 2 window](docs/SR2_01_dialog.png)

## Features

- Surrounds floors, walls or both, inside or outside.
- 7 distribution types, from evenly spaced objects to a fixed number along the whole outline.
- Live plan preview with zoom and pan.
- Exclude rooms, sides and stretches from tabs above the preview: click them on the drawing, or drag along a side to exclude a stretch and adjust its ends.
- Respects door openings and, optionally, window openings, and avoids collisions with furniture, walls and nearby floors.
- Corner handling: empty corners, a perpendicular piece or another object (picked in the plan, the furniture list or the catalog) in each corner.
- Start and end of each side: at the vertex, shifted, fitted to the other strip or with an extra object.
- Same dimensions or same spaces around the whole outline, with limits.
- Vertical stacking, grouping of the generated models, and random or gradual properties (size, elevation, angle, color/texture per material, light power...).
- Interface in 19 languages.

![The 7 distribution types](docs/SR2_03_types_plan.jpg)

## Download and installation

Download `SurroundRoom-2.sh3p` from the [SourceForge ticket](https://sourceforge.net/p/sweethome3d/plug-ins/51/). Double-click it with Sweet Home 3D installed, or copy it into the Sweet Home 3D plug-ins folder and restart the application. Remove older SurroundRoom versions from that folder first.

Requirements: Sweet Home 3D 6.5 or later and Java 8 or later.

## Building from source

This is a NetBeans (Ant) project. The generated folders (`build`, `dist`, `sh3d-dev`) and `nbproject/private` are not in the repository.

1. Put `SweetHome3D-7.5.jar` (the JAR version of Sweet Home 3D from its download page), `j3dcore.jar` and `vecmath.jar` (in `lib/java3d-1.6` of a Sweet Home 3D installation) in a `lib` folder at the root of the project, or set their paths in `nbproject/private/private.properties` with `file.reference.SweetHome3D-7.5.jar`, `file.reference.j3dcore.jar` and `file.reference.vecmath.jar`.
2. Open the folder in NetBeans and run Clean and Build. The plug-in is written to `dist/SurroundRoom-<version>.jar`; rename it to `.sh3p` to install it.
3. Run and Debug start Sweet Home 3D with `sh3d-dev` as its preferences folder, and each compilation puts the plug-in in `sh3d-dev/plugins`.

To install the plug-in in your Sweet Home 3D on every build, add `sh3d.prod.plugins.dir=<path of its plugins folder>` to `nbproject/private/private.properties`.

> **Warning:** with `sh3d.prod.plugins.dir` set, every build deletes the `SurroundRoom.jar`, `SurroundRoom-*.jar`, `SurroundRoom.sh3p` and `SurroundRoom-*.sh3p` files of that folder and puts the new `SurroundRoom-<version>.sh3p` in their place. `dist` and `sh3d-dev/plugins` are cleaned the same way.

## License

Copyright (c) 2024-2026 Oliver Martínez (Ollie_Jess).

SurroundRoom is free software distributed under the GNU General Public License, version 3 or later. See [LICENSE](LICENSE). Sweet Home 3D is distributed separately under the GNU General Public License, version 2 or later.

Thanks to xlance for type 7 and the multi-language support of version 1.3e, which version 2 builds on.
