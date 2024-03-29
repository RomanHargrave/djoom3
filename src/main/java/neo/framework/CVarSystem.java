package neo.framework;

import neo.CM.CollisionModel_local.idCollisionModelManagerLocal;
import neo.Game.Game_local;
import neo.Renderer.Image;
import neo.Renderer.MegaTexture.idMegaTexture;
import neo.Renderer.Model_local.idRenderModelStatic;
import neo.Renderer.RenderSystem_init;
import neo.Renderer.VertexCache.idVertexCache;
import neo.Sound.snd_system;
import static neo.TempDump.atof;
import static neo.TempDump.atoi;
import static neo.TempDump.btoi;
import neo.TempDump.void_callback;
import neo.framework.Async.AsyncNetwork.idAsyncNetwork;
import neo.framework.Async.ServerScan;
import static neo.framework.CVarSystem.idCVarSystemLocal.show.SHOW_DESCRIPTION;
import static neo.framework.CVarSystem.idCVarSystemLocal.show.SHOW_FLAGS;
import static neo.framework.CVarSystem.idCVarSystemLocal.show.SHOW_TYPE;
import static neo.framework.CVarSystem.idCVarSystemLocal.show.SHOW_VALUE;
import neo.framework.CVarSystem.idInternalCVar;
import static neo.framework.CmdSystem.CMD_FL_SYSTEM;
import neo.framework.CmdSystem.argCompletion_t;
import neo.framework.CmdSystem.cmdFunction_t;
import static neo.framework.CmdSystem.cmdSystem;
import neo.framework.CmdSystem.idCmdSystem;
import neo.framework.EventLoop.idEventLoop;
import neo.framework.File_h.idFile;
import static neo.framework.Session.session;
import neo.framework.Session_local.idSessionLocal;
import neo.framework.UsercmdGen.idUsercmdGenLocal;
import neo.idlib.CmdArgs.idCmdArgs;
import neo.idlib.Dict_h.idDict;
import neo.idlib.Dict_h.idKeyValue;
import static neo.idlib.Lib.BIT;
import neo.idlib.Lib.idException;
import static neo.idlib.Lib.idLib.common;
import static neo.idlib.Text.Str.S_COLOR_CYAN;
import static neo.idlib.Text.Str.S_COLOR_GREEN;
import static neo.idlib.Text.Str.S_COLOR_RED;
import static neo.idlib.Text.Str.S_COLOR_WHITE;
import neo.idlib.Text.Str.idStr;
import static neo.idlib.Text.Str.va;
import neo.idlib.containers.HashIndex.idHashIndex;
import neo.idlib.containers.List.cmp_t;
import neo.idlib.containers.List.idList;
import neo.sys.win_net;
import neo.ui.DeviceContext;
import neo.ui.Window.idWindow;

//	CVar Registration
//
//	Each DLL using CVars has to declare a private copy of the static variable
//	idCVar::staticVars like this: idCVar * idCVar::staticVars = NULL;
public class CVarSystem {

    private static idCVarSystemLocal localCVarSystem = new idCVarSystemLocal();
    public static idCVarSystem cvarSystem = localCVarSystem;

    static {
        /**
         * CVARS eager init: </br>
         * jvm's don't generally preload static fields until a class is
         * referenced, so this little trick is for all the scattered cvars.
         * could as well move them all to a single class, but we want to retain
         * a hint of...
         */
        ServerScan scan = new ServerScan();
        snd_system snd = new snd_system();
        win_net net = new win_net();
        Console con = new Console();
        idSessionLocal session = new idSessionLocal();
        idAsyncNetwork async = new idAsyncNetwork();
        EventLoop event = new EventLoop();
        idEventLoop loop = new idEventLoop();
        DeclManager decl = new DeclManager();
        RenderSystem_init render = new RenderSystem_init();
        Image image = new Image();
        idMegaTexture texture = new idMegaTexture();
        idRenderModelStatic model = new idRenderModelStatic();
        idUsercmdGenLocal usr = new idUsercmdGenLocal();
        idVertexCache vertex = new idVertexCache();
        Game_local game = new Game_local();
        idCollisionModelManagerLocal collision = new idCollisionModelManagerLocal();
        idWindow window = new idWindow(null);
        DeviceContext context = new DeviceContext();
    }

    /*
     ===============================================================================

     Console Variables (CVars) are used to hold scalar or string variables
     that can be changed or displayed at the console as well as accessed
     directly in code.

     CVars are mostly used to hold settings that can be changed from the
     console or saved to and loaded from configuration files. CVars are also
     occasionally used to communicate information between different modules
     of the program.

     CVars are restricted from having the same names as console commands to
     keep the console interface from being ambiguous.

     CVars can be accessed from the console in three ways:
     cvarName			prints the current value
     cvarName X			sets the value to X if the variable exists
     set cvarName X		as above, but creates the CVar if not present

     CVars may be declared in the global namespace, in classes and in functions.
     However declarations in classes and functions should always be static to
     save space and time. Making CVars static does not change their
     functionality due to their global nature.

     CVars should be contructed only through one of the constructors with name,
     value, flags and description. The name, value and description parameters
     to the constructor have to be static strings, do not use va() or the like
     functions returning a string.

     CVars may be declared multiple times using the same name string. However,
     they will all reference the same value and changing the value of one CVar
     changes the value of all CVars with the same name.

     CVars should always be declared with the correct type flag: CVAR_BOOL,
     CVAR_INTEGER or CVAR_FLOAT. If no such flag is specified the CVar
     defaults to type string. If the CVAR_BOOL flag is used there is no need
     to specify an argument auto-completion function because the CVar gets
     one assigned automatically.

     CVars are automatically range checked based on their type and any min/max
     or valid string set specified in the constructor.

     CVars are always considered cheats except when CVAR_NOCHEAT, CVAR_INIT,
     CVAR_ROM, CVAR_ARCHIVE, CVAR_USERINFO, CVAR_SERVERINFO, CVAR_NETWORKSYNC
     is set.

     ===============================================================================
     */
    public static final int CVAR_ALL = -1;		// all flags
    public static final int CVAR_BOOL = BIT(0);     	// variable is a boolean
    public static final int CVAR_INTEGER = BIT(1);	// variable is an longeger
    public static final int CVAR_FLOAT = BIT(2);	// variable is a float
    public static final int CVAR_SYSTEM = BIT(3);	// system variable
    public static final int CVAR_RENDERER = BIT(4); 	// renderer variable
    public static final int CVAR_SOUND = BIT(5);        // sound variable
    public static final int CVAR_GUI = BIT(6);          // gui variable
    public static final int CVAR_GAME = BIT(7);         // game variable
    public static final int CVAR_TOOL = BIT(8);         // tool variable
    public static final int CVAR_USERINFO = BIT(9);	// sent to servers; available to menu
    public static final int CVAR_SERVERINFO = BIT(10);	// sent from servers; available to menu
    public static final int CVAR_NETWORKSYNC = BIT(11);	// cvar is synced from the server to clients
    public static final int CVAR_STATIC = BIT(12);	// statically declared; not user created
    public static final int CVAR_CHEAT = BIT(13);	// variable is considered a cheat
    public static final int CVAR_NOCHEAT = BIT(14);	// variable is not considered a cheat
    public static final int CVAR_INIT = BIT(15);	// can only be set from the command-line
    public static final int CVAR_ROM = BIT(16);		// display only; cannot be set by user at all
    public static final int CVAR_ARCHIVE = BIT(17);	// set to cause it to be saved to a config file
    public static final int CVAR_MODIFIED = BIT(18);	// set when the variable is modified

    /*
     ===============================================================================

     idCVar

     ===============================================================================
     */
    public static class idCVar {

        protected String name;				// name
        protected String value;				// value
        protected String description;			// description
        protected int flags;				// CVAR_? flags
        protected float valueMin;			// minimum value
        protected float valueMax;			// maximum value
        protected String[] valueStrings;		// valid value strings
        protected argCompletion_t valueCompletion;	// value auto-completion function
        protected int integerValue;			// atoi( string )
        protected float floatValue;			// atof( value )
        protected idCVar internalVar;			// internal cvar
        protected idCVar next;				// next statically declared cvar
        //
        private static idCVar staticVars = null;
        private static final idCVar ID_CVAR_0xFFFFFFFF = new idCVar();
        //
        //

        // Never use the default constructor.
        private idCVar() {
            assert (this.getClass() != idCVar.class);
        }

        // Always use one of the following constructors.
        public idCVar(final String name, final String value, int flags, final String description) {
            if (null == valueCompletion && (flags & CVAR_BOOL) != 0) {
                valueCompletion = idCmdSystem.ArgCompletion_Boolean.getInstance();
            }
            Init(name, value, flags, description, 1, -1, null, null);
        }

        public idCVar(final String name, final String value, int flags, final String description, argCompletion_t valueCompletion) {
            if (null == valueCompletion && (flags & CVAR_BOOL) != 0) {
                valueCompletion = idCmdSystem.ArgCompletion_Boolean.getInstance();
            }
            Init(name, value, flags, description, 1, -1, null, valueCompletion);
        }

        public idCVar(final String name, final String value, int flags, final String description, float valueMin, float valueMax) {
            Init(name, value, flags, description, valueMin, valueMax, null, null);
        }

        public idCVar(final String name, final String value, int flags, final String description, float valueMin, float valueMax, argCompletion_t valueCompletion) {
            Init(name, value, flags, description, valueMin, valueMax, null, valueCompletion);
        }

        public idCVar(final String name, final String value, int flags, final String description, final String[] valueStrings) {
            Init(name, value, flags, description, 1, -1, valueStrings, null);
        }

        public idCVar(final String name, final String value, int flags, final String description, final String[] valueStrings, argCompletion_t valueCompletion) {
            Init(name, value, flags, description, 1, -1, valueStrings, valueCompletion);
        }
//
//public	virtual					~idCVar( void ) {}
//

        public String GetName() {
            return internalVar.name;
        }

        public int GetFlags() {
            return internalVar.flags;
        }

        public String GetDescription() {
            return internalVar.description;
        }

        public float GetMinValue() {
            return internalVar.valueMin;
        }

        public float GetMaxValue() {
            return internalVar.valueMax;
        }

        public String[] GetValueStrings() {
            return valueStrings;
        }

        public argCompletion_t GetValueCompletion() {
            return valueCompletion;
        }

        public boolean IsModified() {
            return (internalVar.flags & CVAR_MODIFIED) != 0;
        }

        public void SetModified() {
            internalVar.flags |= CVAR_MODIFIED;
        }

        public void ClearModified() {
            internalVar.flags &= ~CVAR_MODIFIED;
        }

        public String GetString() {
            return internalVar.value;
        }

        public boolean GetBool() {
            return (internalVar.integerValue != 0);
        }

        public int GetInteger() {
            return internalVar.integerValue;
        }

        public float GetFloat() {
            return internalVar.floatValue;
        }

        public void SetString(final String value) {
            internalVar.InternalSetString(value);
        }

        public void SetBool(final boolean value) {
            internalVar.InternalSetBool(value);
        }

        public void SetInteger(final int value) {
            internalVar.InternalSetInteger(value);
        }

        public void SetFloat(final float value) {
            internalVar.InternalSetFloat(value);
        }

        public void SetInternalVar(idCVar cvar) {
            internalVar = cvar;
        }

        /*
         ===============================================================================

         CVar Registration

         Each DLL using CVars has to declare a private copy of the static variable
         idCVar::staticVars like this: idCVar * idCVar::staticVars = NULL;
         Furthermore idCVar::RegisterStaticVars() has to be called after the
         cvarSystem pointer is set when the DLL is first initialized.

         ===============================================================================
         */
        public static void RegisterStaticVars() {
            if (staticVars != ID_CVAR_0xFFFFFFFF) {
                for (idCVar cvar = staticVars; cvar != null; cvar = cvar.next) {
                    cvarSystem.Register(cvar);
                }
                staticVars = ID_CVAR_0xFFFFFFFF;
            }
        }


        /*
         ===============================================================================

         CVar Registration

         Each DLL using CVars has to declare a private copy of the static variable
         idCVar::staticVars like this: idCVar * idCVar::staticVars = NULL;
         Furthermore idCVar::RegisterStaticVars() has to be called after the
         cvarSystem pointer is set when the DLL is first initialized.

         ===============================================================================
         */
        private void Init(final String name, final String value, int flags, final String description, float valueMin, float valueMax, final String[] valueStrings, argCompletion_t valueCompletion) {
            this.name = name;
            this.value = value;
            this.flags = flags;
            this.description = description;
            this.flags = (int) (flags | CVAR_STATIC);
            this.valueMin = valueMin;
            this.valueMax = valueMax;
            this.valueStrings = valueStrings;
            this.valueCompletion = valueCompletion;
            this.integerValue = 0;
            this.floatValue = 0.0f;
            this.internalVar = this;
            if (staticVars != ID_CVAR_0xFFFFFFFF) {
                this.next = staticVars;
                staticVars = this;
            } else {
                cvarSystem.Register(this);
            }
        }

        //virtual
        protected void InternalSetString(final String newValue) {
        }

        protected void InternalSetBool(final boolean newValue) {
        }

        protected void InternalSetInteger(final int newValue) {
        }

        protected void InternalSetFloat(final float newValue) {
        }

    };

    /**
     * ===============================================================================
     *
     * idCVarSystem
     *
     * ===============================================================================
     */
    public static abstract class idCVarSystem {

//	public abstract					~idCVarSystem( ) {}
        public abstract void Init() throws idException;

        public abstract void Shutdown();

        public abstract boolean IsInitialized();

        // Registers a CVar.
        public abstract void Register(idCVar cvar) throws idException;

        // Finds the CVar with the given name.
        // Returns NULL if there is no CVar with the given name.
        public abstract idCVar Find(final String name);

        // Sets the value of a CVar by name.
        public abstract void SetCVarString(final String name, final String value);

        public abstract void SetCVarString(final String name, final String value, int flags);

        public abstract void SetCVarBool(final String name, final boolean value);

        public abstract void SetCVarBool(final String name, final boolean value, int flags);

        public abstract void SetCVarInteger(final String name, final int value);

        public abstract void SetCVarInteger(final String name, final int value, int flags);

        public abstract void SetCVarFloat(final String name, final float value);

        public abstract void SetCVarFloat(final String name, final float value, int flags);

        // Gets the value of a CVar by name.
        public abstract String GetCVarString(final String name);

        public abstract boolean GetCVarBool(final String name);

        public abstract int GetCVarInteger(final String name);

        public abstract float GetCVarFloat(final String name);

        // Called by the command system when argv(0) doesn't match a known command.
        // Returns true if argv(0) is a variable reference and prints or changes the CVar.
        public abstract boolean Command(final idCmdArgs args) throws idException;

        // Command and argument completion using callback for each valid string.
        public abstract void CommandCompletion(void_callback<String> callback/*, final String s*/) throws idException;

        public abstract void ArgCompletion(final String cmdString, void_callback<String> callback/*, final String s*/) throws idException;

        // Sets/gets/clears modified flags that tell what kind of CVars have changed.
        public abstract void SetModifiedFlags(int flags);

        public abstract int GetModifiedFlags();

        public abstract void ClearModifiedFlags(int flags);

        // Resets variables with one of the given flags set.
        public abstract void ResetFlaggedVariables(int flags) throws idException;

        // Removes auto-completion from the flagged variables.
        public abstract void RemoveFlaggedAutoCompletion(int flags);

        // Writes variables with one of the given flags set to the given file.
        public abstract void WriteFlaggedVariables(int flags, final String setCmd, idFile f);

        // Moves CVars to and from dictionaries.
        public abstract idDict MoveCVarsToDict(int flags) throws idException;

        public abstract void SetCVarsFromDict(final idDict dict) throws idException;
    };

    public static class idInternalCVar extends idCVar {
        // friend class idCVarSystemLocal;

        private idStr nameString;			// name
        private idStr resetString;			// resetting will change to this value
        private idStr valueString;			// value
        private idStr descriptionString;		// description
        //
        //

        public idInternalCVar() {
        }

        public idInternalCVar(final String newName, final String newValue, int newFlags) {
            nameString = new idStr(newName);
            name = newName;
            valueString = new idStr(newValue);
            value = newValue;
            resetString = new idStr(newValue);
            descriptionString = new idStr();
            description = "";
            flags = (int) ((newFlags & ~CVAR_STATIC) | CVAR_MODIFIED);
            valueMin = 1;
            valueMax = -1;
            valueStrings = null;
            valueCompletion = null;
            UpdateValue();
            UpdateCheat();
            internalVar = this;
        }

        public idInternalCVar(final idCVar cvar) {
            nameString = new idStr(cvar.GetName());
            name = cvar.GetName();
            valueString = new idStr(cvar.GetString());
            value = cvar.GetString();
            resetString = new idStr(cvar.GetString());
            descriptionString = new idStr(cvar.GetDescription());
            description = cvar.GetDescription();
            flags = (int) (cvar.GetFlags() | CVAR_MODIFIED);
            valueMin = cvar.GetMinValue();
            valueMax = cvar.GetMaxValue();
            valueStrings = CopyValueStrings(cvar.GetValueStrings());
            valueCompletion = cvar.GetValueCompletion();
            UpdateValue();
            UpdateCheat();
            internalVar = this;
        }
//	// virtual					~idInternalCVar( void );
//

        public String[] CopyValueStrings(final String[] strings) {
//	int i, totalLength;
//	const char **ptr;
//	char *str;
//
//	if ( !strings ) {
//		return NULL;
//	}
//
//	totalLength = 0;
//	for ( i = 0; strings[i] != NULL; i++ ) {
//		totalLength += idStr::Length( strings[i] ) + 1;
//	}
//
//	ptr = (const char **) Mem_Alloc( ( i + 1 ) * sizeof( char * ) + totalLength );
//	str = (char *) (((byte *)ptr) + ( i + 1 ) * sizeof( char * ) );
//
//	for ( i = 0; strings[i] != NULL; i++ ) {
//		ptr[i] = str;
//		strcpy( str, strings[i] );
//		str += idStr::Length( strings[i] ) + 1;
//	}
//	ptr[i] = NULL;
//
//	return ptr;

//            return Arrays.copyOf(strings, strings.length);
            return null == strings ? null : strings.clone();
        }

        public void Update(final idCVar cvar) throws idException {

            // if this is a statically declared variable
            if ((cvar.GetFlags() & CVAR_STATIC) != 0) {

                if ((flags & CVAR_STATIC) != 0) {

                    // the code has more than one static declaration of the same variable, make sure they have the same properties
                    if (resetString.Icmp(cvar.GetString()) != 0) {
                        common.Warning("CVar '%s' declared multiple times with different initial value", nameString);
                    }
                    if ((flags & (CVAR_BOOL | CVAR_INTEGER | CVAR_FLOAT)) != (cvar.GetFlags() & (CVAR_BOOL | CVAR_INTEGER | CVAR_FLOAT))) {
                        common.Warning("CVar '%s' declared multiple times with different type", nameString);
                    }
                    if (valueMin != cvar.GetMinValue() || valueMax != cvar.GetMaxValue()) {
                        common.Warning("CVar '%s' declared multiple times with different minimum/maximum", nameString);
                    }

                }

                // the code is now specifying a variable that the user already set a value for, take the new value as the reset value
                resetString = new idStr(cvar.GetString());
                descriptionString = new idStr(cvar.GetDescription());
                description = cvar.GetDescription();
                valueMin = cvar.GetMinValue();
                valueMax = cvar.GetMaxValue();
//                Mem_Free(valueStrings);
                valueStrings = CopyValueStrings(cvar.GetValueStrings());
                valueCompletion = cvar.GetValueCompletion();
                UpdateValue();
                cvarSystem.SetModifiedFlags(cvar.GetFlags());
            }

            flags |= cvar.GetFlags();

            UpdateCheat();

            // only allow one non-empty reset string without a warning
            if (resetString.Length() == 0) {
                resetString = new idStr(cvar.GetString());
            } else if (cvar.GetString() != null && resetString.Cmp(cvar.GetString()) != 0) {
                common.Warning("cvar \"%s\" given initial values: \"%s\" and \"%s\"\n", nameString, resetString, cvar.GetString());
            }
        }

        public void UpdateValue() {
            boolean clamped = false;

            if ((flags & CVAR_BOOL) != 0) {
                integerValue = (atoi(value) != 0 ? 1 : 0);
                floatValue = integerValue;
                if (idStr.Icmp(value, "0") != 0 && idStr.Icmp(value, "1") != 0) {
                    valueString = new idStr((integerValue != 0));
                    value = valueString.toString();
                }
            } else if ((flags & CVAR_INTEGER) != 0) {
                integerValue = atoi(value);
                if (valueMin < valueMax) {
                    if (integerValue < valueMin) {
                        integerValue = (int) valueMin;
                        clamped = true;
                    } else if (integerValue > valueMax) {
                        integerValue = (int) valueMax;
                        clamped = true;
                    }
                }
                if (clamped || !idStr.IsNumeric(value) || idStr.FindChar(value, '.') != 0) {
                    valueString = new idStr(integerValue);
                    value = valueString.toString();
                }
                floatValue = (float) integerValue;
            } else if ((flags & CVAR_FLOAT) != 0) {
                floatValue = atof(value);
                if (valueMin < valueMax) {
                    if (floatValue < valueMin) {
                        floatValue = valueMin;
                        clamped = true;
                    } else if (floatValue > valueMax) {
                        floatValue = valueMax;
                        clamped = true;
                    }
                }
                if (clamped || !idStr.IsNumeric(value)) {
                    valueString = new idStr(floatValue);
                    value = valueString.toString();
                }
                integerValue = (int) floatValue;
            } else {
                if (valueStrings != null && valueStrings.length > 0) {
                    integerValue = 0;
                    for (int i = 0; valueStrings[i] != null; i++) {
                        if (valueString.Icmp(valueStrings[i]) == 0) {
                            integerValue = i;
                            break;
                        }
                    }
                    valueString = new idStr(valueStrings[integerValue]);
                    value = valueStrings[integerValue];
                    floatValue = (float) integerValue;
                } else if (valueString.Length() < 32) {
                    floatValue = atof(value);
                    integerValue = (int) floatValue;
                } else {
                    floatValue = 0.0f;
                    integerValue = 0;
                }
            }
        }

        public void UpdateCheat() {
            // all variables are considered cheats except for a few types
            if ((flags & (CVAR_NOCHEAT | CVAR_INIT | CVAR_ROM | CVAR_ARCHIVE | CVAR_USERINFO | CVAR_SERVERINFO | CVAR_NETWORKSYNC)) != 0) {
                flags &= ~CVAR_CHEAT;
            } else {
                flags |= CVAR_CHEAT;
            }
        }

        public void Set(String newValue, boolean force, boolean fromServer) {
            if (session != null && session.IsMultiplayer() && !fromServer) {
// #ifndef ID_TYPEINFO
                // if ( ( flags & CVAR_NETWORKSYNC ) && idAsyncNetwork::client.IsActive() ) {
                // common.Printf( "%s is a synced over the network and cannot be changed on a multiplayer client.\n", nameString.c_str() );
// #if ID_ALLOW_CHEATS
                // common.Printf( "ID_ALLOW_CHEATS override!\n" );
// #else				
                // return;
// #endif
//		}
// #endif
                if ((flags & CVAR_CHEAT) != 0 && !cvarSystem.GetCVarBool("net_allowCheats")) {
                    common.Printf("%s cannot be changed in multiplayer.\n", nameString);
// #if ID_ALLOW_CHEATS
                    // common.Printf( "ID_ALLOW_CHEATS override!\n" );
// #else				
                    return;
// #endif
                }
            }

            if (null == newValue) {
                newValue = resetString.toString();
            }

            if (!force) {
                if ((flags & CVAR_ROM) != 0) {
                    common.Printf("%s is read only.\n", nameString);
                    return;
                }

                if ((flags & CVAR_INIT) != 0) {
                    common.Printf("%s is write protected.\n", nameString);
                    return;
                }
            }

            if (valueString.Icmp(newValue) == 0) {
                return;
            }

            valueString = new idStr(newValue);
            value = newValue;
            UpdateValue();

            SetModified();
            cvarSystem.SetModifiedFlags(flags);
        }

        public void Reset() {
            valueString = resetString;
            value = valueString.toString();
            UpdateValue();
        }

        @Override
        protected void InternalSetString(final String newValue) {
            Set(newValue, true, false);
        }

        private void InternalServerSetString(final String newValue) throws idException {
            Set(newValue, true, true);
        }

        @Override
        protected void InternalSetBool(final boolean newValue) throws idException {
            Set(Integer.toString(btoi(newValue)), true, false);
        }

        @Override
        protected void InternalSetInteger(final int newValue) throws idException {
            Set(Integer.toString(newValue), true, false);//TODO:parse to string instead.
        }

        @Override
        protected void InternalSetFloat(final float newValue) throws idException {
            Set(Float.toString(newValue), true, false);
        }
    };

    /*
     ===============================================================================

     idCVarSystemLocal

     ===============================================================================
     */
    static class idCVarSystemLocal extends idCVarSystem {

        private boolean initialized;
        private idList<idInternalCVar> cvars;
        private idHashIndex cvarHash;
        private int modifiedFlags;
        // use a static dictionary to MoveCVarsToDict can be used from game
        private static idDict moveCVarsToDict = new idDict();
        //
        //

        public idCVarSystemLocal() {
            initialized = false;
            cvars = new idList<>();
            cvarHash = new idHashIndex();
            modifiedFlags = 0;
        }
//
//public						~idCVarSystemLocal() {}
//

        @Override
        public void Init() throws idException {

            modifiedFlags = 0;

            cmdSystem.AddCommand("toggle", Toggle_f.getInstance(), CMD_FL_SYSTEM, "toggles a cvar");
            cmdSystem.AddCommand("set", Set_f.getInstance(), CMD_FL_SYSTEM, "sets a cvar");
            cmdSystem.AddCommand("sets", SetS_f.getInstance(), CMD_FL_SYSTEM, "sets a cvar and flags it as server info");
            cmdSystem.AddCommand("setu", SetU_f.getInstance(), CMD_FL_SYSTEM, "sets a cvar and flags it as user info");
            cmdSystem.AddCommand("sett", SetT_f.getInstance(), CMD_FL_SYSTEM, "sets a cvar and flags it as tool");
            cmdSystem.AddCommand("seta", SetA_f.getInstance(), CMD_FL_SYSTEM, "sets a cvar and flags it as archive");
            cmdSystem.AddCommand("reset", Reset_f.getInstance(), CMD_FL_SYSTEM, "resets a cvar");
            cmdSystem.AddCommand("listCvars", List_f.getInstance(), CMD_FL_SYSTEM, "lists cvars");
            cmdSystem.AddCommand("cvar_restart", Restart_f.getInstance(), CMD_FL_SYSTEM, "restart the cvar system");

            initialized = true;
        }

        @Override
        public void Shutdown() {
            cvars.DeleteContents(true);
            cvarHash.Free();
            moveCVarsToDict.Clear();
            initialized = false;
        }

        @Override
        public boolean IsInitialized() {
            return initialized;
        }

        @Override
        public void Register(idCVar cvar) throws idException {
            int hash;
            idInternalCVar internal;

            cvar.SetInternalVar(cvar);

            internal = FindInternal(cvar.GetName());

            if (internal != null) {
                internal.Update(cvar);
            } else {
                internal = new idInternalCVar(cvar);
                hash = cvarHash.GenerateKey(internal.nameString.c_str(), false);
                cvarHash.Add(hash, cvars.Append(internal));
            }

            cvar.SetInternalVar(internal);
        }

        @Override
        public idCVar Find(final String name) {
            return FindInternal(name);
        }

        @Override
        public void SetCVarString(final String name, final String value/*, int flags = 0*/) {
            SetCVarString(name, value, 0);
        }

        @Override
        public void SetCVarString(final String name, final String value, int flags) {
            SetInternal(name, value, flags);
        }
        //public	 void			SetCVarBool( final String name, const boolean value/*, int flags = 0*/);

        @Override
        public void SetCVarBool(final String name, final boolean value) {
            SetCVarBool(name, value, 0);
        }

        @Override
        public void SetCVarBool(final String name, final boolean value, int flags) {
            SetInternal(name, "" + value, flags);
        }

        //public	 void			SetCVarInteger( final String name, const int value/*, int flags = 0*/ );
        @Override
        public void SetCVarInteger(final String name, final int value) {
            SetCVarInteger(name, value, 0);
        }

        @Override
        public void SetCVarInteger(final String name, final int value, int flags) {
            SetInternal(name, "" + value, flags);
        }

        @Override
        public void SetCVarFloat(final String name, final float value) {
            SetCVarFloat(name, value, 0);
        }

        @Override
        public void SetCVarFloat(final String name, final float value, int flags) {
            SetInternal(name, "" + value, flags);
        }

        @Override
        public String GetCVarString(final String name) {
            idInternalCVar internal = FindInternal(name);
            if (internal != null) {
                return internal.GetString();
            }
            return "";
        }

        @Override
        public boolean GetCVarBool(final String name) {
            idInternalCVar internal = FindInternal(name);
            if (internal != null) {
                return internal.GetBool();
            }
            return false;
        }

        @Override
        public int GetCVarInteger(final String name) {
            idInternalCVar internal = FindInternal(name);
            if (internal != null) {
                return internal.GetInteger();
            }
            return 0;
        }

        @Override
        public float GetCVarFloat(final String name) {
            idInternalCVar internal = FindInternal(name);
            if (internal != null) {
                return internal.GetFloat();
            }
            return 0.0f;
        }

        @Override
        public boolean Command(final idCmdArgs args) throws idException {
            idInternalCVar internal;

            internal = FindInternal(args.Argv(0));

            if (internal == null) {
                return false;
            }

            if (args.Argc() == 1) {
                // print the variable
                common.Printf("\"%s\" is:\"%s\"" + S_COLOR_WHITE + " default:\"%s\"\n", internal.nameString, internal.valueString, internal.resetString);
                if ( /*idStr.Length*/internal.GetDescription().length() > 0) {
                    common.Printf(S_COLOR_WHITE + "%s\n", internal.GetDescription());
                }
            } else {
                // set the value
                internal.Set(args.Args(), false, false);
            }
            return true;
        }

        @Override
        public void CommandCompletion(void_callback<String> callback) throws idException {
            for (int i = 0; i < cvars.Num(); i++) {
                callback.run(cvars.oGet(i).GetName());
            }
        }

        @Override
        public void ArgCompletion(final String cmdString, void_callback<String> callback) throws idException {
            idCmdArgs args = new idCmdArgs();

            args.TokenizeString(cmdString, false);

            for (int i = 0; i < cvars.Num(); i++) {
                if (null == cvars.oGet(i).valueCompletion) {
                    continue;
                }
                if (idStr.Icmp(args.Argv(0), cvars.oGet(i).nameString.toString()) == 0) {
                    cvars.oGet(i).valueCompletion.run(args, callback);
                    break;
                }
            }
        }

        @Override
        public void SetModifiedFlags(int flags) {
            modifiedFlags |= flags;
        }

        @Override
        public int GetModifiedFlags() {
            return modifiedFlags;
        }

        @Override
        public void ClearModifiedFlags(int flags) {
            modifiedFlags &= ~flags;
        }

        @Override
        public void ResetFlaggedVariables(int flags) throws idException {
            for (int i = 0; i < cvars.Num(); i++) {
                idInternalCVar cvar = cvars.oGet(i);
                if ((cvar.GetFlags() & flags) != 0) {
                    cvar.Set(null, true, true);
                }
            }
        }

        @Override
        public void RemoveFlaggedAutoCompletion(int flags) {
            for (int i = 0; i < cvars.Num(); i++) {
                idInternalCVar cvar = cvars.oGet(i);
                if ((cvar.GetFlags() & flags) != 0) {
                    cvar.valueCompletion = null;
                }
            }
        }

        /*
         ============
         idCVarSystemLocal::WriteFlaggedVariables

         Appends lines containing "set variable value" for all variables
         with the "flags" flag set to true.
         ============
         */
        @Override
        public void WriteFlaggedVariables(int flags, final String setCmd, idFile f) {
            for (int i = 0; i < cvars.Num(); i++) {
                idInternalCVar cvar = cvars.oGet(i);
                if ((cvar.GetFlags() & flags) != 0) {
                    f.Printf("%s %s \"%s\"\n", setCmd, cvar.GetName(), cvar.GetString());
                }
            }
        }

        @Override
        public idDict MoveCVarsToDict(int flags) throws idException {
            moveCVarsToDict.Clear();
            for (int i = 0; i < cvars.Num(); i++) {
                idCVar cvar = cvars.oGet(i);
                if ((cvar.GetFlags() & flags) != 0) {
                    moveCVarsToDict.Set(cvar.GetName(), cvar.GetString());
                }
            }
            return moveCVarsToDict;
        }

        @Override
        public void SetCVarsFromDict(final idDict dict) throws idException {
            idInternalCVar internal;

            for (int i = 0; i < dict.GetNumKeyVals(); i++) {
                final idKeyValue kv = dict.GetKeyVal(i);
                internal = FindInternal(kv.GetKey().toString());
                if (internal != null) {
                    internal.InternalServerSetString(kv.GetValue().toString());
                }
            }
        }
//
//public	void					RegisterInternal( idCVar cvar );

        public idInternalCVar FindInternal(final String name) {
            int hash = cvarHash.GenerateKey(name, false);
            for (int i = cvarHash.First(hash); i != -1; i = cvarHash.Next(i)) {
                if (cvars.oGet(i).nameString.Icmp(name) == 0) {
                    return cvars.oGet(i);
                }
            }
            return null;
        }

        public void SetInternal(final String name, final String value, int flags) {
            int hash;
            idInternalCVar internal;

            internal = FindInternal(name);

            if (internal != null) {
                internal.InternalSetString(value);
                internal.flags |= flags & ~CVAR_STATIC;
                internal.UpdateCheat();
            } else {
                internal = new idInternalCVar(name, value, flags);
                hash = cvarHash.GenerateKey(internal.nameString.c_str(), false);
                cvarHash.Add(hash, cvars.Append(internal));
            }
        }

        /*
         ============
         idCVarSystemLocal::Toggle_f
         ============
         */
        static class Toggle_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new Toggle_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                int argc, i;
                float current, set;
                String text;

                argc = args.Argc();
                if (argc < 2) {
                    common.Printf("usage:\n"
                            + "   toggle <variable>  - toggles between 0 and 1\n"
                            + "   toggle <variable> <value> - toggles between 0 and <value>\n"
                            + "   toggle <variable> [string 1] [string 2]...[string n] - cycles through all strings\n");
                    return;
                }

                idInternalCVar cvar = localCVarSystem.FindInternal(args.Argv(1));

                if (null == cvar) {
                    common.Warning("Toggle_f: cvar \"%s\" not found", args.Argv(1));
                    return;
                }

                if (argc > 3) {
                    // cycle through multiple values
                    text = cvar.GetString();
                    for (i = 2; i < argc; i++) {
                        if (0 == idStr.Icmp(text, args.Argv(i))) {
                            // point to next value
                            i++;
                            break;
                        }
                    }
                    if (i >= argc) {
                        i = 2;
                    }

                    common.Printf("set %s = %s\n", args.Argv(1), args.Argv(i));
                    cvar.Set(va("%s", args.Argv(i)), false, false);
                } else {
                    // toggle between 0 and 1
                    current = cvar.GetFloat();
                    if (argc == 3) {
                        set = atof(args.Argv(2));
                    } else {
                        set = 1.0f;
                    }
                    if (current == 0.0f) {
                        current = set;
                    } else {
                        current = 0.0f;
                    }
                    common.Printf("set %s = %f\n", args.Argv(1), current);
                    cvar.Set(new idStr(current).toString(), false, false);
                }
            }
        };

        static class Set_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new Set_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                final String str;

                str = args.Args(2, args.Argc() - 1);
                localCVarSystem.SetCVarString(args.Argv(1), str);
            }
        };

        static class SetS_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new SetS_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                idInternalCVar cvar;

                Set_f.getInstance().run(args);
                cvar = localCVarSystem.FindInternal(args.Argv(1));
                if (null == cvar) {
                    return;
                }
                cvar.flags |= CVAR_SERVERINFO | CVAR_ARCHIVE;
            }
        };

        static class SetU_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new SetU_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                idInternalCVar cvar;

                Set_f.getInstance().run(args);
                cvar = localCVarSystem.FindInternal(args.Argv(1));
                if (null == cvar) {
                    return;
                }
                cvar.flags |= CVAR_USERINFO | CVAR_ARCHIVE;
            }
        };

        static class SetT_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new SetT_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                idInternalCVar cvar;

                Set_f.getInstance().run(args);
                cvar = localCVarSystem.FindInternal(args.Argv(1));
                if (null == cvar) {
                    return;
                }
                cvar.flags |= CVAR_TOOL;
            }
        };

        static class SetA_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new SetA_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                idInternalCVar cvar;

                Set_f.getInstance().run(args);
                cvar = localCVarSystem.FindInternal(args.Argv(1));
//                if (null == cvar) {
//                    return;
//                }

                // FIXME: enable this for ship, so mods can store extra data
                // but during development we don't want obsolete cvars to continue
                // to be saved
//	cvar->flags |= CVAR_ARCHIVE;
            }
        };

        static class Reset_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new Reset_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                idInternalCVar cvar;

                if (args.Argc() != 2) {
                    common.Printf("usage: reset <variable>\n");
                    return;
                }
                cvar = localCVarSystem.FindInternal(args.Argv(1));
                if (null == cvar) {
                    return;
                }

                cvar.Reset();
            }
        };

        static enum show {

            SHOW_VALUE,
            SHOW_DESCRIPTION,
            SHOW_TYPE,
            SHOW_FLAGS
        };

        static void ListByFlags(idCmdArgs args, long /*cvarFlags_t*/ flags) throws idException {
            int i, argNum;
            idStr match, indent = new idStr(), str = new idStr();
            String string;
            idInternalCVar cvar;
            idList<idInternalCVar> cvarList = new idList<>();

            argNum = 1;
            show show = SHOW_VALUE;

            if (idStr.Icmp(args.Argv(argNum), "-") == 0 || idStr.Icmp(args.Argv(argNum), "/") == 0) {
                if (idStr.Icmp(args.Argv(argNum + 1), "help") == 0 || idStr.Icmp(args.Argv(argNum + 1), "?") == 0) {
                    argNum = 3;
                    show = SHOW_DESCRIPTION;
                } else if (idStr.Icmp(args.Argv(argNum + 1), "type") == 0 || idStr.Icmp(args.Argv(argNum + 1), "range") == 0) {
                    argNum = 3;
                    show = SHOW_TYPE;
                } else if (idStr.Icmp(args.Argv(argNum + 1), "flags") == 0) {
                    argNum = 3;
                    show = SHOW_FLAGS;
                }
            }

            if (args.Argc() > argNum) {
                match = new idStr(args.Args(argNum, -1));
                match.Replace(" ", "");
            } else {
                match = new idStr();
            }

            for (i = 0; i < localCVarSystem.cvars.Num(); i++) {
                cvar = localCVarSystem.cvars.oGet(i);

                if (0 == (cvar.GetFlags() & flags)) {
                    continue;
                }

                if (match.Length() != 0 && !cvar.nameString.Filter(match.toString(), false)) {
                    continue;
                }

                cvarList.Append(cvar);
            }

            cvarList.Sort();

            switch (show) {
                case SHOW_VALUE: {
                    for (i = 0; i < cvarList.Num(); i++) {
                        cvar = cvarList.oGet(i);
                        common.Printf(FORMAT_STRING + S_COLOR_WHITE + "\"%s\"\n", cvar.nameString, cvar.valueString);
                    }
                    break;
                }
                case SHOW_DESCRIPTION: {
                    indent.Fill(' ', NUM_NAME_CHARS);
                    indent.Insert("\n", 0);

                    for (i = 0; i < cvarList.Num(); i++) {
                        cvar = cvarList.oGet(i);
                        common.Printf(FORMAT_STRING + S_COLOR_WHITE + "%s\n", cvar.nameString, CreateColumn(cvar.GetDescription(), NUM_DESCRIPTION_CHARS, indent.toString(), str));
                    }
                    break;
                }
                case SHOW_TYPE: {
                    for (i = 0; i < cvarList.Num(); i++) {
                        cvar = cvarList.oGet(i);
                        if ((cvar.GetFlags() & CVAR_BOOL) != 0) {
                            common.Printf(FORMAT_STRING + S_COLOR_CYAN + "bool\n", cvar.GetName());
                        } else if ((cvar.GetFlags() & CVAR_INTEGER) != 0) {
                            if (cvar.GetMinValue() < cvar.GetMaxValue()) {
                                common.Printf(FORMAT_STRING + S_COLOR_GREEN + "int " + S_COLOR_WHITE + "[%d, %d]\n", cvar.GetName(), (int) cvar.GetMinValue(), (int) cvar.GetMaxValue());
                            } else {
                                common.Printf(FORMAT_STRING + S_COLOR_GREEN + "int\n", cvar.GetName());
                            }
                        } else if ((cvar.GetFlags() & CVAR_FLOAT) != 0) {
                            if (cvar.GetMinValue() < cvar.GetMaxValue()) {
                                common.Printf(FORMAT_STRING + S_COLOR_RED + "float " + S_COLOR_WHITE + "[%s, %s]\n", cvar.GetName(), new idStr(cvar.GetMinValue()).toString(), new idStr(cvar.GetMaxValue()).toString());
                            } else {
                                common.Printf(FORMAT_STRING + S_COLOR_RED + "float\n", cvar.GetName());
                            }
                        } else if (cvar.GetValueStrings() != null) {
                            common.Printf(FORMAT_STRING + S_COLOR_WHITE + "string " + S_COLOR_WHITE + "[", cvar.GetName());
                            for (int j = 0; cvar.GetValueStrings()[j] != null; j++) {
                                if (j != 0) {
                                    common.Printf(S_COLOR_WHITE + ", %s", cvar.GetValueStrings()[j]);
                                } else {
                                    common.Printf(S_COLOR_WHITE + "%s", cvar.GetValueStrings()[j]);
                                }
                            }
                            common.Printf(S_COLOR_WHITE + "]\n");
                        } else {
                            common.Printf(FORMAT_STRING + S_COLOR_WHITE + "string\n", cvar.GetName());
                        }
                    }
                    break;
                }
                case SHOW_FLAGS: {
                    for (i = 0; i < cvarList.Num(); i++) {
                        cvar = cvarList.oGet(i);
                        common.Printf(FORMAT_STRING, cvar.GetName());
                        string = "";
                        if ((cvar.GetFlags() & CVAR_BOOL) != 0) {
                            string += S_COLOR_CYAN + "B ";
                        } else if ((cvar.GetFlags() & CVAR_INTEGER) != 0) {
                            string += S_COLOR_GREEN + "I ";
                        } else if ((cvar.GetFlags() & CVAR_FLOAT) != 0) {
                            string += S_COLOR_RED + "F ";
                        } else {
                            string += S_COLOR_WHITE + "S ";
                        }
                        if ((cvar.GetFlags() & CVAR_SYSTEM) != 0) {
                            string += S_COLOR_WHITE + "SYS  ";
                        } else if ((cvar.GetFlags() & CVAR_RENDERER) != 0) {
                            string += S_COLOR_WHITE + "RNDR ";
                        } else if ((cvar.GetFlags() & CVAR_SOUND) != 0) {
                            string += S_COLOR_WHITE + "SND  ";
                        } else if ((cvar.GetFlags() & CVAR_GUI) != 0) {
                            string += S_COLOR_WHITE + "GUI  ";
                        } else if ((cvar.GetFlags() & CVAR_GAME) != 0) {
                            string += S_COLOR_WHITE + "GAME ";
                        } else if ((cvar.GetFlags() & CVAR_TOOL) != 0) {
                            string += S_COLOR_WHITE + "TOOL ";
                        } else {
                            string += S_COLOR_WHITE + "     ";
                        }
                        string += ((cvar.GetFlags() & CVAR_USERINFO) != 0) ? "UI " : "   ";
                        string += ((cvar.GetFlags() & CVAR_SERVERINFO) != 0) ? "SI " : "   ";
                        string += ((cvar.GetFlags() & CVAR_STATIC) != 0) ? "ST " : "   ";
                        string += ((cvar.GetFlags() & CVAR_CHEAT) != 0) ? "CH " : "   ";
                        string += ((cvar.GetFlags() & CVAR_INIT) != 0) ? "IN " : "   ";
                        string += ((cvar.GetFlags() & CVAR_ROM) != 0) ? "RO " : "   ";
                        string += ((cvar.GetFlags() & CVAR_ARCHIVE) != 0) ? "AR " : "   ";
                        string += ((cvar.GetFlags() & CVAR_MODIFIED) != 0) ? "MO " : "   ";
                        string += "\n";
                        common.Printf(string);
                    }
                    break;
                }
            }

            common.Printf("\n%d cvars listed\n\n", cvarList.Num());
            common.Printf("listCvar [search string]          = list cvar values\n"
                    + "listCvar -help [search string]    = list cvar descriptions\n"
                    + "listCvar -type [search string]    = list cvar types\n"
                    + "listCvar -flags [search string]   = list cvar flags\n");
        }

        static class List_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new List_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) throws idException {
                ListByFlags(args, CVAR_ALL);
            }
        };

        static class Restart_f extends cmdFunction_t {

            private static final cmdFunction_t instance = new Restart_f();

            public static cmdFunction_t getInstance() {
                return instance;
            }

            @Override
            public void run(idCmdArgs args) {
                int i, hash;
                idInternalCVar cvar;

                for (i = 0; i < localCVarSystem.cvars.Num(); i++) {
                    cvar = localCVarSystem.cvars.oGet(i);

                    // don't mess with rom values
                    if ((cvar.flags & (CVAR_ROM | CVAR_INIT)) != 0) {
                        continue;
                    }

                    // throw out any variables the user created
                    if (0 == (cvar.flags & CVAR_STATIC)) {
                        hash = localCVarSystem.cvarHash.GenerateKey(cvar.nameString.toString(), false);
//			delete cvar;
                        localCVarSystem.cvars.RemoveIndex(i);
                        localCVarSystem.cvarHash.RemoveIndex(hash, i);
                        i--;
                        continue;
                    }

                    cvar.Reset();
                }

            }
        };
    };
    //    
    //
    static final int NUM_COLUMNS = 77;		// 78 - 1, or (80 x 2 - 2) / 2 - 2
    static final int NUM_NAME_CHARS = 33;
    static final int NUM_DESCRIPTION_CHARS = (NUM_COLUMNS - NUM_NAME_CHARS);
    static final String FORMAT_STRING = "%-32s ";

    static String CreateColumn(final String textString, int columnWidth, final String indent, idStr string) {
        int i, lastLine;
        char[] text = textString.toCharArray();

        string.Clear();
        for (lastLine = i = 0; /*text[i] != '\0'*/ i < text.length; i++) {
            if (i - lastLine >= columnWidth || text[i] == '\n') {
                while (i > 0 && text[i] > ' ' && text[i] != '/' && text[i] != ',' && text[i] != '\\') {
                    i--;
                }
                while (lastLine < i) {
                    string.Append(text[lastLine++]);
                }
                string.Append(indent);
                lastLine++;
            }
        }
        while (lastLine < i) {
            string.Append(text[lastLine++]);
        }
        return string.toString();
    }

    /*
     ============
     idCVarSystemLocal::ListByFlags
     ============
     */
    // NOTE: the const wonkyness is required to make msvc happy
    public static class idListSortCompare implements cmp_t<idInternalCVar> {

        @Override
        public int compare(idInternalCVar a, idInternalCVar b) {
            return idStr.Icmp(a.GetName(), b.GetName());
        }
    }

    public static void setCvarSystem(idCVarSystem cvarSystem) {
        CVarSystem.cvarSystem = CVarSystem.localCVarSystem = (idCVarSystemLocal) cvarSystem;
    }
}
