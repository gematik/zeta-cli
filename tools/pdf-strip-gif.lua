-- Drop image references whose target ends in .gif, so animated GIFs
-- (which would otherwise render as the dark first frame) don't appear
-- in the PDF build. Strips the implicit Figure wrapper too — pandoc
-- promotes a lone image-paragraph into a Figure node whose caption
-- ("Figure 1: …") would otherwise survive even after the Image itself
-- is removed.

local function is_gif(src)
  return type(src) == "string" and src:lower():match("%.gif$") ~= nil
end

local function contains_gif_image(node)
  local hit = false
  pandoc.walk_block(node, {
    Image = function(img)
      if is_gif(img.src) then hit = true end
    end,
  })
  return hit
end

function Image(img)
  if is_gif(img.src) then return {} end
end

function Para(p)
  if #p.content == 1 and p.content[1].t == "Image" and is_gif(p.content[1].src) then
    return {}
  end
end

function Figure(fig)
  if contains_gif_image(fig) then return {} end
end
