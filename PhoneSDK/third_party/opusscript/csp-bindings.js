// Equivalent to Emscripten's embind invoker, using a closure instead of Function.
// The generated factory's local type converters and destructor stack stay intact.
function zb(name, types, classType, invoker, target) {
  const hasThis = types[1] !== null && classType !== null;
  const usesStack = types.slice(1).some(type => type !== null && type.N === undefined);
  return function(...args) {
    if (args.length !== types.length - 2) {
      M('function ' + name + ' called with ' + args.length + ' arguments, expected ' + (types.length - 2));
    }
    const destructors = usesStack ? [] : null;
    const wired = [];
    try {
      if (hasThis) wired.push(types[1].toWireType(destructors, this));
      args.forEach((arg, index) => wired.push(types[index + 2].toWireType(destructors, arg)));
      const result = invoker(target, ...wired);
      return types[0].name === 'void' ? undefined : types[0].fromWireType(result);
    } finally {
      if (usesStack) xb(destructors);
      else wired.forEach((value, index) => {
        const type = types[index + (hasThis ? 1 : 2)];
        if (type.N !== null) type.N(value);
      });
    }
  };
}
