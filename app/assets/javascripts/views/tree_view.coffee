observable = require('models/observable').observable

DEFAULT_OPTIONS = {
  color: {
    background: '#ffffff',
    node: '#ccccdd',
    node_unloaded: '#ddddff',
    node_selected: '#bbbbbb',
    line: '#555555',
  },
  leaf_width: 3, # relative units
  leaf_horizontal_padding: 1, # on each side
  node_height: 10,
  node_vertical_padding: 3,
}

$ = jQuery
_ = window._

class TreeView
  observable(this)

  constructor: (@div, @tree, options={}) ->
    options_color = _.extend({}, options.color, DEFAULT_OPTIONS.color)
    @options = _.extend({}, options, DEFAULT_OPTIONS, { color: options_color })

    $div = $(@div)
    @canvas = $("<canvas width=\"#{$div.width()}\" height=\"#{$div.height()}\"></canvas>")[0]

    $div.append(@canvas)

    this._attach()
    this._redraw()

  _attach: () ->
    @tree.id_tree.observe('root', => this._redraw())
    @tree.id_tree.observe('add', => this._redraw())
    @tree.id_tree.observe('remove', => this._redraw())
    @tree.id_tree.observe('remove-undefined', => this._redraw())

    $(@canvas).on 'click', (e) =>
      x = e.pageX - @canvas.offsetLeft
      y = e.pageY - @canvas.offsetTop
      nodeid = this._pixel_to_nodeid(x, y)
      this._notify('click', nodeid)

  _pixel_to_nodeid: (x, y) ->
    $canvas = $(@canvas)

    doc_index = Math.floor(x / $canvas.width() * @tree.nodes[@tree.id_tree.root].doclist.n)

    levels_to_go = Math.floor(y / $canvas.height() * @tree.height) + 1
    last_node_id = undefined

    node_ids_at_this_level = [ @tree.id_tree.root ]
    while levels_to_go > 0 && node_ids_at_this_level?.length
      docs_seen_at_this_level = 0

      while node_ids_at_this_level.length > 0
        last_node_id = node_ids_at_this_level.shift()
        node = @tree.nodes[last_node_id]
        docs_in_this_node = node?.doclist?.n || this._nodeid_to_n_documents(last_node_id)
        docs_seen_at_this_level += docs_in_this_node

        break if docs_seen_at_this_level > doc_index

      levels_to_go -= 1
      node_ids_at_this_level = @tree.id_tree.children[last_node_id]?.slice()

    last_node_id

  _nodeid_to_n_documents: (nodeid) ->
    exact = @tree.nodes[nodeid]?.doclist?.n
    return exact if exact?

    # Divide the number of documents that must be in unresolved siblings by
    # the number of unresolved siblings.
    parent_nodeid = @tree.id_tree.parent[nodeid]
    parent_node = @tree.nodes[parent_nodeid]

    sibling_nodeids = @tree.id_tree.children[parent_nodeid]
    n_unknown_documents = parent_node.doclist.n
    n_unloaded_siblings = 0
    for sibling_nodeid in sibling_nodeids
      sibling = @tree.nodes[sibling_nodeid]
      if sibling?
        n_unknown_documents -= sibling.doclist.n
      else
        n_unloaded_siblings += 1

    n_unknown_documents / n_unloaded_siblings # we know n_unloaded_siblings > 1 because we're here

  _draw_loaded_node: (nodeid, ctx, x, y, w, h) ->
    ctx.fillRect(x, y, w, h)
    ctx.strokeRect(x, y, w, h)

  _draw_unloaded_node: (nodeid, ctx, x, y, w, h) ->
    ctx.save()
    ctx.fillStyle = @options.color.node_unloaded

    ctx.beginPath()
    ctx.moveTo(x, y + h * 0.5)
    ctx.quadraticCurveTo(x, y, x + w * 0.5, y)
    ctx.quadraticCurveTo(x + w, y, x + w, y + h * 0.5)
    ctx.quadraticCurveTo(x + w, y + h, x + w * 0.5, y + h)
    ctx.quadraticCurveTo(x, y + h, x, y + h * 0.5)
    ctx.fill()
    ctx.stroke()

    ctx.restore()

  _draw_node: (nodeid, ctx, spxx, spxy) ->
    is_loaded = @tree.nodes[nodeid]?
    n_documents = this._nodeid_to_n_documents(nodeid)

    width = n_documents * @options.leaf_width * spxx
    padding_x = n_documents * @options.leaf_horizontal_padding * spxx
    height = @options.node_height * spxy
    padding_y = @options.node_vertical_padding * spxy

    if !is_loaded
      this._draw_unloaded_node(nodeid, ctx, padding_x, padding_y, width, height)
    else
      this._draw_loaded_node(nodeid, ctx, padding_x, padding_y, width, height)

      ctx.save()
      ctx.translate(0, height + 2 * padding_y)

      node_x = padding_x + width * 0.5

      c = @tree.id_tree.children[nodeid]
      for child_id in c
        width2 = this._draw_node(child_id, ctx, spxx, spxy)

        child_x = width2 * 0.5

        ctx.beginPath()
        ctx.moveTo(node_x, -padding_y)
        ctx.bezierCurveTo(node_x, 0, child_x, 0, child_x, padding_y)
        ctx.stroke()

        ctx.translate(width2, 0) # move to the right, for the next child
        node_x -= width2

      ctx.restore()

    width + 2 * padding_x

  _redraw: () ->
    ctx = @canvas.getContext('2d')

    width = Math.ceil($(@canvas).width())
    height = Math.ceil($(@canvas).height())

    ctx.fillStyle = @options.color.background
    ctx.strokeStyle = @options.color.line
    ctx.fillRect(0, 0, width, height)

    return if @tree.height == 0

    # We render to pixels, but our calculations are done with integers in
    # subpixel space. Multiply by "spxx" and "spxy" to convert to pixel space.
    # Why not scale()? Because we want fine strokes
    spx_width = @tree.nodes[@tree.id_tree.root].doclist.n \
      * (@options.leaf_width + 2 * @options.leaf_horizontal_padding)
    spx_height = @tree.height * (@options.node_height + 2 * @options.node_vertical_padding)

    spxx = width / spx_width
    spxy = height / spx_height

    ctx.fillStyle = @options.color.node
    ctx.lineWidth = 1

    this._draw_node(@tree.id_tree.root, ctx, spxx, spxy)

exports = require.make_export_object('views/tree_view')
exports.TreeView = TreeView
